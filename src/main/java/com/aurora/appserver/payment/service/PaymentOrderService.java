package com.aurora.appserver.payment.service;

import com.aurora.appserver.auth.repository.UserRepository;
import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.exception.BusinessException;
import com.aurora.appserver.membership.domain.MembershipPlanEntity;
import com.aurora.appserver.membership.domain.PlanCode;
import com.aurora.appserver.membership.repository.MembershipPlanRepository;
import com.aurora.appserver.membership.service.MembershipService;
import com.aurora.appserver.payment.config.PaymentProperties;
import com.aurora.appserver.payment.domain.PaymentOrderEntity;
import com.aurora.appserver.payment.domain.PaymentOrderEventEntity;
import com.aurora.appserver.payment.domain.PaymentOrderRequestEntity;
import com.aurora.appserver.payment.domain.PaymentOrderStatus;
import com.aurora.appserver.payment.domain.PaymentOrderTrigger;
import com.aurora.appserver.payment.provider.CheckoutRequest;
import com.aurora.appserver.payment.provider.PaymentLink;
import com.aurora.appserver.payment.provider.PaymentProvider;
import com.aurora.appserver.payment.provider.PaymentProviderRegistry;
import com.aurora.appserver.payment.repository.PaymentOrderEventRepository;
import com.aurora.appserver.payment.repository.PaymentOrderRepository;
import com.aurora.appserver.payment.repository.PaymentOrderRequestRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates and reads payment orders. Price, duration and lifetime flag are snapshotted from
 * membership_plans; the client only chooses planCode and supplies an idempotency key.
 *
 * <p>Concurrency: creation runs under a per-user row lock (users FOR UPDATE), so concurrent
 * requests for the same user serialize; unique constraints remain the final backstop.
 */
@Service
public class PaymentOrderService {

    private static final Logger log = LoggerFactory.getLogger(PaymentOrderService.class);
    private static final Pattern CLIENT_REQUEST_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");
    private static final Pattern ORDER_NO = Pattern.compile("^[A-Z0-9]{1,32}$");
    private static final int MAX_LOCK_ATTEMPTS = 3;

    private final PaymentOrderRepository orderRepository;
    private final PaymentOrderRequestRepository requestRepository;
    private final PaymentOrderEventRepository eventRepository;
    private final MembershipPlanRepository planRepository;
    private final MembershipService membershipService;
    private final UserRepository userRepository;
    private final PaymentProviderRegistry providerRegistry;
    private final OrderNoGenerator orderNoGenerator;
    private final PaymentProperties properties;
    private final Clock clock;
    private final PaymentOrderService self;

    public PaymentOrderService(
            PaymentOrderRepository orderRepository,
            PaymentOrderRequestRepository requestRepository,
            PaymentOrderEventRepository eventRepository,
            MembershipPlanRepository planRepository,
            MembershipService membershipService,
            UserRepository userRepository,
            PaymentProviderRegistry providerRegistry,
            OrderNoGenerator orderNoGenerator,
            PaymentProperties properties,
            Clock clock,
            @Lazy PaymentOrderService self
    ) {
        this.orderRepository = orderRepository;
        this.requestRepository = requestRepository;
        this.eventRepository = eventRepository;
        this.planRepository = planRepository;
        this.membershipService = membershipService;
        this.userRepository = userRepository;
        this.providerRegistry = providerRegistry;
        this.orderNoGenerator = orderNoGenerator;
        this.properties = properties;
        this.clock = clock;
        this.self = self;
    }

    public record OrderView(
            PaymentOrderEntity order,
            PaymentOrderStatus effectiveStatus,
            PaymentLink payLink,
            Instant serverTime
    ) {
    }

    public OrderView create(Long appId, Long userId, String planCodeRaw, String clientRequestIdRaw) {
        String clientRequestId = validateClientRequestId(clientRequestIdRaw);
        PlanCode planCode = parsePlanCode(planCodeRaw);
        PaymentProvider provider = providerRegistry.requireActive();

        Optional<PaymentOrderEntity> replay = findByClientRequest(appId, userId, clientRequestId, planCode);
        if (replay.isPresent()) {
            return toView(replay.get());
        }

        PaymentOrderEntity order = createWithRetry(appId, userId, clientRequestId, planCode, provider);
        return toView(order);
    }

    @Transactional(readOnly = true)
    public OrderView get(Long appId, Long userId, String orderNo) {
        if (orderNo == null || !ORDER_NO.matcher(orderNo).matches()) {
            throw new BusinessException(ApiErrorCode.PAYMENT_ORDER_NOT_FOUND);
        }
        PaymentOrderEntity order = orderRepository.findByAppIdAndUserIdAndOrderNo(appId, userId, orderNo)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.PAYMENT_ORDER_NOT_FOUND));
        return toView(order);
    }

    private PaymentOrderEntity createWithRetry(
            Long appId, Long userId, String clientRequestId, PlanCode planCode, PaymentProvider provider) {
        for (int attempt = 1; ; attempt++) {
            try {
                return self.createOrReuseInTx(appId, userId, clientRequestId, planCode, provider);
            } catch (DataIntegrityViolationException ex) {
                // Transaction already rolled back; the winning request's mapping is now visible.
                Optional<PaymentOrderEntity> winner =
                        findByClientRequest(appId, userId, clientRequestId, planCode);
                if (winner.isPresent()) {
                    return winner.get();
                }
                if (attempt >= MAX_LOCK_ATTEMPTS) {
                    throw ex;
                }
            } catch (PessimisticLockingFailureException ex) {
                if (attempt >= MAX_LOCK_ATTEMPTS) {
                    throw ex;
                }
                log.warn("Payment order lock conflict, retrying attempt={}", attempt);
            }
        }
    }

    @Transactional
    public PaymentOrderEntity createOrReuseInTx(
            Long appId, Long userId, String clientRequestId, PlanCode planCode, PaymentProvider provider) {
        userRepository.findForUpdate(appId, userId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.UNAUTHORIZED));

        Optional<PaymentOrderEntity> replay = findByClientRequest(appId, userId, clientRequestId, planCode);
        if (replay.isPresent()) {
            return replay.get();
        }

        MembershipPlanEntity plan = planRepository.findByAppIdAndPlanCode(appId, planCode)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.MEMBERSHIP_PLAN_NOT_FOUND));
        if (!plan.isActive()) {
            throw new BusinessException(ApiErrorCode.MEMBERSHIP_PLAN_DISABLED);
        }
        if (membershipService.getStatus(appId, userId).lifetime()) {
            throw new BusinessException(ApiErrorCode.PAYMENT_LIFETIME_ALREADY_ACTIVE);
        }

        Instant now = clock.instant();
        Optional<PaymentOrderEntity> reusable = orderRepository
                .findFirstByAppIdAndUserIdAndPlanCodeAndStatusAndProviderAndEnvironmentAndExpiresAtAfterOrderByCreatedAtDesc(
                        appId,
                        userId,
                        planCode,
                        PaymentOrderStatus.PENDING_PAYMENT,
                        provider.code(),
                        properties.getEnvironment(),
                        now.plus(properties.getReuseMinRemaining()));
        if (reusable.isPresent()) {
            PaymentOrderEntity order = reusable.get();
            requestRepository.saveAndFlush(PaymentOrderRequestEntity.create(
                    appId, userId, clientRequestId, order.getId(), planCode, true));
            eventRepository.save(PaymentOrderEventEntity.of(
                    order.getId(),
                    order.getStatus(),
                    order.getStatus(),
                    PaymentOrderTrigger.REUSE,
                    "clientRequestId=" + clientRequestId));
            log.info("Payment order reused appId={} userId={} orderNo={} plan={}",
                    appId, userId, order.getOrderNo(), planCode);
            return order;
        }

        PaymentOrderEntity order = orderRepository.saveAndFlush(PaymentOrderEntity.createPending(
                appId,
                userId,
                orderNoGenerator.next(now),
                clientRequestId,
                plan,
                provider.code(),
                properties.getEnvironment(),
                provider.merchantPid(),
                now.plus(properties.getOrderTtl())));
        requestRepository.saveAndFlush(PaymentOrderRequestEntity.create(
                appId, userId, clientRequestId, order.getId(), planCode, false));
        eventRepository.save(PaymentOrderEventEntity.of(
                order.getId(), null, PaymentOrderStatus.PENDING_PAYMENT, PaymentOrderTrigger.CREATE, null));
        log.info("Payment order created appId={} userId={} orderNo={} plan={} amountFen={} provider={} env={}",
                appId, userId, order.getOrderNo(), planCode, order.getAmountFen(),
                order.getProvider(), order.getEnvironment());
        return order;
    }

    private Optional<PaymentOrderEntity> findByClientRequest(
            Long appId, Long userId, String clientRequestId, PlanCode planCode) {
        return requestRepository.findByAppIdAndUserIdAndClientRequestId(appId, userId, clientRequestId)
                .map(request -> {
                    if (request.getPlanCode() != planCode) {
                        throw new BusinessException(ApiErrorCode.PAYMENT_CLIENT_REQUEST_CONFLICT);
                    }
                    return orderRepository.findByAppIdAndUserIdAndId(appId, userId, request.getOrderId())
                            .orElseThrow(() -> new BusinessException(ApiErrorCode.INTERNAL_ERROR));
                });
    }

    private OrderView toView(PaymentOrderEntity order) {
        Instant now = clock.instant();
        PaymentOrderStatus effective = order.effectiveStatus(now);
        PaymentLink link = null;
        if (order.isPayable(now)) {
            link = providerRegistry.find(order.getProvider())
                    .filter(PaymentProvider::isConfigured)
                    .map(p -> p.buildCheckoutLink(new CheckoutRequest(
                            order.getOrderNo(), order.getAmountFen(), order.getPlanDisplayName())))
                    .orElse(null);
        }
        return new OrderView(order, effective, link, now);
    }

    private static String validateClientRequestId(String raw) {
        if (raw == null || !CLIENT_REQUEST_ID.matcher(raw.trim()).matches()) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED);
        }
        return raw.trim();
    }

    private static PlanCode parsePlanCode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED);
        }
        try {
            return PlanCode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ApiErrorCode.MEMBERSHIP_PLAN_NOT_FOUND);
        }
    }
}
