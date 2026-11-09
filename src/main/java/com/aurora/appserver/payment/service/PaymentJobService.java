package com.aurora.appserver.payment.service;

import com.aurora.appserver.payment.config.PaymentProperties;
import com.aurora.appserver.payment.domain.PaymentOrderEntity;
import com.aurora.appserver.payment.domain.PaymentOrderStatus;
import com.aurora.appserver.payment.domain.PaymentOrderTrigger;
import com.aurora.appserver.payment.repository.PaymentNotificationRepository;
import com.aurora.appserver.payment.repository.PaymentOrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Compensation cycle. All state lives in the database, so a restarted (or additional) instance
 * simply resumes: due work is selected by status/timestamps and claimed under the order row lock.
 */
@Service
public class PaymentJobService {

    private static final Logger log = LoggerFactory.getLogger(PaymentJobService.class);

    public record CycleReport(int fulfillAttempts, int pendingQueries, int latePatrolQueries, int notificationRetries) {
    }

    private final PaymentOrderRepository orderRepository;
    private final PaymentNotificationRepository notificationRepository;
    private final PaymentConfirmationService confirmationService;
    private final PaymentFulfillmentService fulfillmentService;
    private final PaymentProperties properties;
    private final Clock clock;

    public PaymentJobService(
            PaymentOrderRepository orderRepository,
            PaymentNotificationRepository notificationRepository,
            PaymentConfirmationService confirmationService,
            PaymentFulfillmentService fulfillmentService,
            PaymentProperties properties,
            Clock clock
    ) {
        this.orderRepository = orderRepository;
        this.notificationRepository = notificationRepository;
        this.confirmationService = confirmationService;
        this.fulfillmentService = fulfillmentService;
        this.properties = properties;
        this.clock = clock;
    }

    public CycleReport runCycle() {
        return runCycle(0L);
    }

    /** @param minOrderIdExclusive only consider orders with a larger id (0 = all) */
    public CycleReport runCycle(long minOrderIdExclusive) {
        int fulfilled = runFulfillment(minOrderIdExclusive);
        int pending = runPendingQueries(minOrderIdExclusive);
        int patrol = runLatePatrol(minOrderIdExclusive);
        int notifications = runNotificationRetries(minOrderIdExclusive);
        // Orders confirmed during this cycle are fulfilled right away by the confirmation path;
        // a second pass picks up anything that became due meanwhile.
        fulfilled += runFulfillment(minOrderIdExclusive);
        CycleReport report = new CycleReport(fulfilled, pending, patrol, notifications);
        if (fulfilled + pending + patrol + notifications > 0) {
            log.info("Payment job cycle {}", report);
        }
        return report;
    }

    int runFulfillment(long minOrderIdExclusive) {
        int processed = 0;
        long cursor = minOrderIdExclusive;
        while (processed < maxPerCycle()) {
            List<Long> ids = orderRepository.findIdsDueForFulfillment(clock.instant(), cursor, page());
            if (ids.isEmpty()) {
                break;
            }
            for (Long id : ids) {
                cursor = id;
                safely(() -> fulfillmentService.fulfill(id), "fulfill", id);
                processed++;
            }
        }
        return processed;
    }

    int runPendingQueries(long minOrderIdExclusive) {
        PaymentProperties.Jobs jobs = properties.getJobs();
        int processed = 0;
        long cursor = minOrderIdExclusive;
        while (processed < maxPerCycle()) {
            Instant now = clock.instant();
            List<Long> ids = orderRepository.findPendingIdsDueForQuery(
                    now.minus(jobs.getPendingQueryInterval()), cursor, page());
            if (ids.isEmpty()) {
                break;
            }
            for (Long id : ids) {
                cursor = id;
                if (confirmationService.claimQueryInTx(
                        id, Set.of(PaymentOrderStatus.PENDING_PAYMENT), jobs.getPendingQueryInterval())) {
                    safely(() -> confirmationService.confirmByQuery(id, PaymentOrderTrigger.QUERY, true), "query", id);
                    processed++;
                }
            }
        }
        return processed;
    }

    int runLatePatrol(long minOrderIdExclusive) {
        PaymentProperties.Jobs jobs = properties.getJobs();
        int processed = 0;
        long cursor = minOrderIdExclusive;
        while (processed < maxPerCycle()) {
            Instant now = clock.instant();
            List<Long> ids = orderRepository.findExpiredIdsForLatePatrol(
                    now.minus(jobs.getLatePatrolWindow()), now.minus(jobs.getLatePatrolInterval()), cursor, page());
            if (ids.isEmpty()) {
                break;
            }
            for (Long id : ids) {
                cursor = id;
                if (confirmationService.claimQueryInTx(
                        id, Set.of(PaymentOrderStatus.EXPIRED), jobs.getLatePatrolInterval())) {
                    safely(() -> confirmationService.confirmByQuery(id, PaymentOrderTrigger.QUERY, true), "patrol", id);
                    processed++;
                }
            }
        }
        return processed;
    }

    /**
     * Verified notifications whose confirmation is still pending — processed regardless of the
     * order's age, so a trusted late notification beyond the patrol window is still honoured.
     */
    int runNotificationRetries(long minOrderIdExclusive) {
        PaymentProperties.Jobs jobs = properties.getJobs();
        int processed = 0;
        while (processed < maxPerCycle()) {
            Instant now = clock.instant();
            List<String> orderNos = notificationRepository.findOrderNosPendingConfirm(
                    now.minus(jobs.getNotificationRetryWindow()),
                    now.minus(jobs.getNotificationRetryInterval()),
                    page());
            if (orderNos.isEmpty()) {
                break;
            }
            for (String orderNo : orderNos) {
                // Touch first so the next page never returns the same order again in this cycle.
                confirmationService.touchNotifications(orderNo);
                processed++;
                PaymentOrderEntity order = orderRepository.findByOrderNo(orderNo).orElse(null);
                if (order == null || order.getId() <= minOrderIdExclusive) {
                    continue;
                }
                safely(() -> confirmationService.confirmByQuery(order.getId(), PaymentOrderTrigger.NOTIFY, false),
                        "notification-retry", order.getId());
            }
        }
        return processed;
    }

    private PageRequest page() {
        return PageRequest.of(0, Math.max(1, properties.getJobs().getBatchSize()));
    }

    private int maxPerCycle() {
        return Math.max(1, properties.getJobs().getMaxPerCycle());
    }

    private static void safely(Runnable action, String step, Long orderId) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            log.warn("Payment job step failed step={} orderId={} error={}", step, orderId, ex.getClass().getSimpleName());
        }
    }
}
