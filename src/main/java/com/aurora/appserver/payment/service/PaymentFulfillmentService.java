package com.aurora.appserver.payment.service;

import com.aurora.appserver.common.exception.BusinessException;
import com.aurora.appserver.payment.config.PaymentProperties;
import com.aurora.appserver.payment.domain.PaymentOrderEntity;
import com.aurora.appserver.payment.domain.PaymentOrderEventEntity;
import com.aurora.appserver.payment.domain.PaymentOrderStatus;
import com.aurora.appserver.payment.domain.PaymentOrderTrigger;
import com.aurora.appserver.payment.repository.PaymentOrderEventRepository;
import com.aurora.appserver.payment.repository.PaymentOrderRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PAID -> FULFILLED. A worker first claims the order under its row lock (lease via next_fulfill_at),
 * then grants outside that lock; the membership ledger's UNIQUE(app_id, PAYMENT, order_no) makes the
 * grant idempotent even if a lease expires mid-flight or several instances race.
 */
@Service
public class PaymentFulfillmentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentFulfillmentService.class);
    private static final List<Duration> BACKOFF = List.of(
            Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2),
            Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(30));
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);

    public enum Outcome {
        FULFILLED,
        ALREADY_FULFILLED,
        NOT_CLAIMED,
        FAILED
    }

    private final PaymentOrderRepository orderRepository;
    private final PaymentOrderEventRepository eventRepository;
    private final MembershipFulfillmentPort fulfillmentPort;
    private final PaymentProperties properties;
    private final Clock clock;
    private final PaymentFulfillmentService self;

    public PaymentFulfillmentService(
            PaymentOrderRepository orderRepository,
            PaymentOrderEventRepository eventRepository,
            MembershipFulfillmentPort fulfillmentPort,
            PaymentProperties properties,
            Clock clock,
            @Lazy PaymentFulfillmentService self
    ) {
        this.orderRepository = orderRepository;
        this.eventRepository = eventRepository;
        this.fulfillmentPort = fulfillmentPort;
        this.properties = properties;
        this.clock = clock;
        this.self = self;
    }

    record Claim(Long appId, String orderNo, int attempts) {
    }

    public Outcome fulfill(Long orderId) {
        Optional<Claim> claim = self.claimInTx(orderId);
        if (claim.isEmpty()) {
            return orderRepository.findById(orderId)
                    .filter(o -> o.getStatus() == PaymentOrderStatus.FULFILLED)
                    .map(o -> Outcome.ALREADY_FULFILLED)
                    .orElse(Outcome.NOT_CLAIMED);
        }
        Claim c = claim.get();
        Long grantId;
        try {
            grantId = fulfillmentPort.grantForPaidOrder(c.appId(), c.orderNo());
        } catch (RuntimeException ex) {
            self.recordFailureInTx(orderId, c.attempts(), describe(ex));
            return Outcome.FAILED;
        }
        return self.completeInTx(orderId, grantId) ? Outcome.FULFILLED : Outcome.ALREADY_FULFILLED;
    }

    @Transactional
    public Optional<Claim> claimInTx(Long orderId) {
        PaymentOrderEntity order = orderRepository.findForUpdate(orderId).orElse(null);
        if (order == null || !order.claimFulfillment(clock.instant(), properties.getJobs().getFulfillLease())) {
            return Optional.empty();
        }
        orderRepository.save(order);
        return Optional.of(new Claim(order.getAppId(), order.getOrderNo(), order.getFulfillAttempts()));
    }

    @Transactional
    public boolean completeInTx(Long orderId, Long grantId) {
        PaymentOrderEntity order = orderRepository.findForUpdate(orderId).orElseThrow();
        if (!order.markFulfilled(clock.instant(), grantId)) {
            return false;
        }
        orderRepository.save(order);
        eventRepository.save(PaymentOrderEventEntity.of(
                orderId, PaymentOrderStatus.PAID, PaymentOrderStatus.FULFILLED,
                PaymentOrderTrigger.FULFILL, "grantId=" + grantId));
        log.info("Payment order fulfilled orderNo={} grantId={}", order.getOrderNo(), grantId);
        return true;
    }

    @Transactional
    public void recordFailureInTx(Long orderId, int attempts, String error) {
        PaymentOrderEntity order = orderRepository.findForUpdate(orderId).orElseThrow();
        Instant retryAt = clock.instant().plus(backoff(attempts));
        order.recordFulfillFailure(retryAt, error);
        orderRepository.save(order);
        if (attempts >= properties.getJobs().getFulfillAlertAttempts()) {
            log.error("ALERT payment fulfillment still failing orderNo={} attempts={} error={}",
                    order.getOrderNo(), attempts, error);
        } else {
            log.warn("Payment fulfillment failed orderNo={} attempts={} retryAt={} error={}",
                    order.getOrderNo(), attempts, retryAt, error);
        }
    }

    static Duration backoff(int attempts) {
        int index = Math.max(0, attempts - 1);
        return index < BACKOFF.size() ? BACKOFF.get(index) : MAX_BACKOFF;
    }

    private static String describe(RuntimeException ex) {
        if (ex instanceof BusinessException be) {
            return "BusinessException:" + be.getErrorCode().name();
        }
        return ex.getClass().getSimpleName();
    }
}
