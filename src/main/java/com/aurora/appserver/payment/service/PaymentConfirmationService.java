package com.aurora.appserver.payment.service;

import com.aurora.appserver.payment.config.PaymentProperties;
import com.aurora.appserver.payment.domain.PaymentAnomaly;
import com.aurora.appserver.payment.domain.PaymentOrderEntity;
import com.aurora.appserver.payment.domain.PaymentOrderEventEntity;
import com.aurora.appserver.payment.domain.PaymentOrderStatus;
import com.aurora.appserver.payment.domain.PaymentOrderTrigger;
import com.aurora.appserver.payment.domain.PaymentProviderCallEntity;
import com.aurora.appserver.payment.provider.PaymentProvider;
import com.aurora.appserver.payment.provider.PaymentProviderRegistry;
import com.aurora.appserver.payment.provider.PaymentQueryResult;
import com.aurora.appserver.payment.repository.PaymentNotificationRepository;
import com.aurora.appserver.payment.repository.PaymentOrderEventRepository;
import com.aurora.appserver.payment.repository.PaymentOrderRepository;
import com.aurora.appserver.payment.repository.PaymentProviderCallRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single entry for confirming payment: an authoritative provider query is the only way an
 * order becomes PAID. Notifications, jobs and polling all funnel through {@link #confirmByQuery}.
 * State changes happen under the order row lock; queries run outside any transaction.
 */
@Service
public class PaymentConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfirmationService.class);

    public enum Outcome {
        PAID_CONFIRMED,
        ALREADY_PAID,
        NOT_PAID,
        EXPIRED,
        INCONCLUSIVE,
        MISMATCH,
        SKIPPED
    }

    private final PaymentOrderRepository orderRepository;
    private final PaymentOrderEventRepository eventRepository;
    private final PaymentNotificationRepository notificationRepository;
    private final PaymentProviderCallRepository callRepository;
    private final PaymentProviderRegistry providerRegistry;
    private final PaymentFulfillmentService fulfillmentService;
    private final PaymentProperties properties;
    private final Clock clock;
    private final PaymentConfirmationService self;

    public PaymentConfirmationService(
            PaymentOrderRepository orderRepository,
            PaymentOrderEventRepository eventRepository,
            PaymentNotificationRepository notificationRepository,
            PaymentProviderCallRepository callRepository,
            PaymentProviderRegistry providerRegistry,
            PaymentFulfillmentService fulfillmentService,
            PaymentProperties properties,
            Clock clock,
            @Lazy PaymentConfirmationService self
    ) {
        this.orderRepository = orderRepository;
        this.eventRepository = eventRepository;
        this.notificationRepository = notificationRepository;
        this.callRepository = callRepository;
        this.providerRegistry = providerRegistry;
        this.fulfillmentService = fulfillmentService;
        this.properties = properties;
        this.clock = clock;
        this.self = self;
    }

    /**
     * @param alreadyClaimed true when a job already recorded this query attempt via {@link #claimQueryInTx}
     */
    public Outcome confirmByQuery(Long orderId, PaymentOrderTrigger trigger, boolean alreadyClaimed) {
        PaymentOrderEntity order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return Outcome.SKIPPED;
        }
        if (order.getStatus() == PaymentOrderStatus.PAID || order.getStatus() == PaymentOrderStatus.FULFILLED) {
            self.markNotificationsApplied(order.getOrderNo());
            if (order.getStatus() == PaymentOrderStatus.PAID) {
                fulfillmentService.fulfill(orderId);
            }
            return Outcome.ALREADY_PAID;
        }

        Optional<PaymentProvider> provider = providerRegistry.find(order.getProvider())
                .filter(PaymentProvider::isConfigured);
        if (provider.isEmpty()) {
            log.warn("Payment provider unavailable for order orderNo={} provider={}",
                    order.getOrderNo(), order.getProvider());
            return Outcome.SKIPPED;
        }
        if (!alreadyClaimed) {
            self.recordQueryAttemptInTx(orderId);
        }

        long started = System.nanoTime();
        PaymentQueryResult result;
        try {
            result = provider.get().queryOrder(order.getOrderNo());
        } catch (RuntimeException ex) {
            result = PaymentQueryResult.inconclusive(
                    PaymentQueryResult.Outcome.ERROR, null, null, "provider_" + ex.getClass().getSimpleName());
        }
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        callRepository.save(PaymentProviderCallEntity.query(order.getProvider(), order.getOrderNo(), result, durationMs));

        return switch (result.outcome()) {
            case PAID -> handlePaid(order, result, trigger);
            case NOT_CONFIRMED -> handleNotPaid(orderId, order.getOrderNo());
            case ERROR, TIMEOUT -> handleInconclusive(orderId, order.getOrderNo(), result);
        };
    }

    private Outcome handlePaid(PaymentOrderEntity order, PaymentQueryResult result, PaymentOrderTrigger trigger) {
        PaymentAnomaly mismatch = validate(order, result);
        if (mismatch != null) {
            self.markAnomalyInTx(order.getId(), mismatch, trigger);
            log.error("ALERT payment query mismatch orderNo={} anomaly={} – membership NOT granted",
                    order.getOrderNo(), mismatch);
            return Outcome.MISMATCH;
        }
        boolean transitioned;
        try {
            transitioned = self.applyPaidInTx(order.getId(), result, trigger);
        } catch (DataIntegrityViolationException ex) {
            self.markAnomalyInTx(order.getId(), PaymentAnomaly.QUERY_ORDER_MISMATCH, trigger);
            log.error("ALERT provider trade number conflict orderNo={} – membership NOT granted", order.getOrderNo());
            return Outcome.MISMATCH;
        }
        fulfillmentService.fulfill(order.getId());
        return transitioned ? Outcome.PAID_CONFIRMED : Outcome.ALREADY_PAID;
    }

    private Outcome handleNotPaid(Long orderId, String orderNo) {
        self.touchNotifications(orderNo);
        return self.expireIfDueInTx(orderId, false) ? Outcome.EXPIRED : Outcome.NOT_PAID;
    }

    private Outcome handleInconclusive(Long orderId, String orderNo, PaymentQueryResult result) {
        self.touchNotifications(orderNo);
        log.info("Payment query inconclusive orderNo={} outcome={} code={} msg={}",
                orderNo, result.outcome(), result.providerCode(), result.providerMsg());
        return self.expireIfDueInTx(orderId, true) ? Outcome.EXPIRED : Outcome.INCONCLUSIVE;
    }

    private static PaymentAnomaly validate(PaymentOrderEntity order, PaymentQueryResult result) {
        if (result.orderNo() == null || !result.orderNo().equals(order.getOrderNo())) {
            return PaymentAnomaly.QUERY_ORDER_MISMATCH;
        }
        if (order.getMerchantPid() == null || !order.getMerchantPid().equals(result.merchantPid())) {
            return PaymentAnomaly.QUERY_MERCHANT_MISMATCH;
        }
        if (result.amountFen().isEmpty() || result.amountFen().get() != order.getAmountFen()) {
            return PaymentAnomaly.QUERY_AMOUNT_MISMATCH;
        }
        return null;
    }

    // ------------------------------------------------------------------ transactional steps

    /** Job claim: returns true when this worker may query now (multi-instance safe via row lock). */
    @Transactional
    public boolean claimQueryInTx(Long orderId, Set<PaymentOrderStatus> allowed, Duration minInterval) {
        PaymentOrderEntity order = orderRepository.findForUpdate(orderId).orElse(null);
        if (order == null || !allowed.contains(order.getStatus())) {
            return false;
        }
        if (!order.claimQuery(clock.instant(), minInterval)) {
            return false;
        }
        orderRepository.save(order);
        return true;
    }

    @Transactional
    public void recordQueryAttemptInTx(Long orderId) {
        orderRepository.findForUpdate(orderId).ifPresent(order -> {
            order.recordQueryAttempt(clock.instant());
            orderRepository.save(order);
        });
    }

    @Transactional
    public boolean applyPaidInTx(Long orderId, PaymentQueryResult result, PaymentOrderTrigger trigger) {
        Instant now = clock.instant();
        PaymentOrderEntity order = orderRepository.findForUpdate(orderId).orElseThrow();
        PaymentOrderStatus from = order.getStatus();
        boolean changed = order.markPaid(
                now,
                result.providerTradeNo(),
                result.upstreamTradeNo(),
                result.channelRaw(),
                result.amountFen().orElseThrow(),
                result.paidAtRaw());
        if (changed) {
            orderRepository.saveAndFlush(order);
            eventRepository.save(PaymentOrderEventEntity.of(
                    orderId, from, PaymentOrderStatus.PAID, trigger,
                    order.isLatePaid() ? "late_payment" : null));
            if (order.isLatePaid()) {
                log.warn("ALERT late payment confirmed after local expiry orderNo={}", order.getOrderNo());
            } else {
                log.info("Payment confirmed orderNo={} trigger={}", order.getOrderNo(), trigger);
            }
        }
        notificationRepository.markApplied(order.getOrderNo(), now);
        return changed;
    }

    /**
     * PENDING_PAYMENT -> EXPIRED once past expiry. A conclusive "not paid" answer expires
     * immediately; inconclusive answers only after the grace period and enough attempts.
     */
    @Transactional
    public boolean expireIfDueInTx(Long orderId, boolean inconclusive) {
        Instant now = clock.instant();
        PaymentOrderEntity order = orderRepository.findForUpdate(orderId).orElseThrow();
        if (order.getStatus() != PaymentOrderStatus.PENDING_PAYMENT || now.isBefore(order.getExpiresAt())) {
            return false;
        }
        if (inconclusive) {
            PaymentProperties.Jobs jobs = properties.getJobs();
            boolean graceOver = !now.isBefore(order.getExpiresAt().plus(jobs.getInconclusiveExpireGrace()));
            if (!graceOver || order.getQueryAttempts() < jobs.getInconclusiveMinAttempts()) {
                return false;
            }
        }
        if (!order.markExpired(now)) {
            return false;
        }
        if (inconclusive) {
            order.markAnomaly(PaymentAnomaly.EXPIRED_INCONCLUSIVE);
        }
        orderRepository.save(order);
        eventRepository.save(PaymentOrderEventEntity.of(
                orderId, PaymentOrderStatus.PENDING_PAYMENT, PaymentOrderStatus.EXPIRED,
                PaymentOrderTrigger.EXPIRE_JOB, inconclusive ? "inconclusive_queries" : "provider_not_paid"));
        log.info("Payment order expired orderNo={} inconclusive={}", order.getOrderNo(), inconclusive);
        return true;
    }

    @Transactional
    public void markAnomalyInTx(Long orderId, PaymentAnomaly anomaly, PaymentOrderTrigger trigger) {
        PaymentOrderEntity order = orderRepository.findForUpdate(orderId).orElseThrow();
        order.markAnomaly(anomaly);
        orderRepository.save(order);
        eventRepository.save(PaymentOrderEventEntity.of(
                orderId, order.getStatus(), order.getStatus(), trigger, "anomaly=" + anomaly.name()));
    }

    @Transactional
    public void markNotificationsApplied(String orderNo) {
        notificationRepository.markApplied(orderNo, clock.instant());
    }

    @Transactional
    public void touchNotifications(String orderNo) {
        notificationRepository.touchPendingConfirm(orderNo, clock.instant());
    }
}
