package com.aurora.appserver.payment.service;

import com.aurora.appserver.payment.alert.PaymentAlertService;
import com.aurora.appserver.payment.alert.PaymentAlertType;
import com.aurora.appserver.payment.config.PaymentProperties;
import com.aurora.appserver.payment.domain.PaymentAnomaly;
import com.aurora.appserver.payment.domain.PaymentNotificationEntity;
import com.aurora.appserver.payment.domain.PaymentNotificationEntity.Result;
import com.aurora.appserver.payment.domain.PaymentOrderEntity;
import com.aurora.appserver.payment.domain.PaymentOrderStatus;
import com.aurora.appserver.payment.domain.PaymentOrderTrigger;
import com.aurora.appserver.payment.domain.PaymentProviderCode;
import com.aurora.appserver.payment.provider.PaymentNotificationRejectedException;
import com.aurora.appserver.payment.provider.PaymentProvider;
import com.aurora.appserver.payment.provider.PaymentProviderRegistry;
import com.aurora.appserver.payment.provider.VerifiedNotification;
import com.aurora.appserver.payment.provider.yusen.YusenSigner;
import com.aurora.appserver.payment.repository.PaymentNotificationRepository;
import com.aurora.appserver.payment.repository.PaymentOrderRepository;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Inbound provider notifications. A notification is only a trigger: after merchant id, signature,
 * order and amount checks it is recorded as PENDING_CONFIRM and an authoritative provider query
 * decides. The HTTP request waits at most {@code notify-confirm-wait} for that query.
 */
@Service
public class PaymentNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentNotificationService.class);

    private final PaymentProviderRegistry providerRegistry;
    private final PaymentNotificationRepository notificationRepository;
    private final PaymentOrderRepository orderRepository;
    private final PaymentConfirmationService confirmationService;
    private final PaymentProperties properties;
    private final Clock clock;
    private final PaymentAlertService alertService;
    private final ThreadPoolExecutor confirmExecutor;

    public PaymentNotificationService(
            PaymentProviderRegistry providerRegistry,
            PaymentNotificationRepository notificationRepository,
            PaymentOrderRepository orderRepository,
            PaymentConfirmationService confirmationService,
            PaymentProperties properties,
            Clock clock,
            PaymentAlertService alertService
    ) {
        this.providerRegistry = providerRegistry;
        this.notificationRepository = notificationRepository;
        this.orderRepository = orderRepository;
        this.confirmationService = confirmationService;
        this.properties = properties;
        this.clock = clock;
        this.alertService = alertService;
        AtomicInteger seq = new AtomicInteger();
        this.confirmExecutor = new ThreadPoolExecutor(
                2, 4, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "pay-confirm-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdown() {
        confirmExecutor.shutdown();
    }

    /** @return the response body to send to the provider */
    public String handle(PaymentProviderCode code, Map<String, String> params, String remoteIp) {
        Optional<PaymentProvider> maybeProvider = providerRegistry.find(code).filter(PaymentProvider::isConfigured);
        if (maybeProvider.isEmpty()) {
            log.warn("Notification for unconfigured provider={} rejected", code);
            return "fail";
        }
        PaymentProvider provider = maybeProvider.get();
        String dedupeKey = dedupeKey(params);

        VerifiedNotification verified;
        try {
            verified = provider.verifyNotification(params);
        } catch (PaymentNotificationRejectedException ex) {
            boolean pidProblem = ex.getReason() == PaymentNotificationRejectedException.Reason.PID_MISMATCH;
            record(code, params, null, !pidProblem, false, dedupeKey,
                    pidProblem ? Result.REJECTED_PID : Result.REJECTED_SIGN, remoteIp);
            alertService.alert(PaymentAlertType.NOTIFY_REJECTED, params.get("out_trade_no"),
                    "provider=" + code + " reason=" + ex.getReason() + " ip=" + remoteIp);
            return provider.notificationReject();
        }

        Optional<PaymentNotificationEntity> seen = notificationRepository.findByProviderAndDedupeKey(code, dedupeKey);
        if (seen.isPresent() && seen.get().getProcessResult() != Result.PENDING_CONFIRM) {
            log.info("Duplicate payment notification orderNo={} previous={}",
                    verified.orderNo(), seen.get().getProcessResult());
            return provider.notificationAck();
        }

        if (!verified.tradeSuccess()) {
            record(code, params, verified, true, true, dedupeKey, Result.IGNORED_STATUS, remoteIp);
            return provider.notificationAck();
        }

        Optional<PaymentOrderEntity> maybeOrder = orderRepository.findByOrderNo(verified.orderNo())
                .filter(o -> o.getProvider() == code);
        if (maybeOrder.isEmpty()) {
            record(code, params, verified, true, true, dedupeKey, Result.ORDER_NOT_FOUND, remoteIp);
            alertService.alert(PaymentAlertType.NOTIFY_UNKNOWN_ORDER, verified.orderNo(), "provider=" + code);
            return provider.notificationAck();
        }
        PaymentOrderEntity order = maybeOrder.get();

        if (order.getMerchantPid() == null || !order.getMerchantPid().equals(params.get("pid"))) {
            record(code, params, verified, false, true, dedupeKey, Result.REJECTED_PID, remoteIp);
            confirmationService.markAnomalyInTx(order.getId(), PaymentAnomaly.NOTIFY_MERCHANT_MISMATCH,
                    PaymentOrderTrigger.NOTIFY);
            alertService.alert(PaymentAlertType.NOTIFY_MERCHANT_MISMATCH, order.getOrderNo(), "provider=" + code);
            return provider.notificationAck();
        }
        if (verified.amountFen().isEmpty() || verified.amountFen().get() != order.getAmountFen()) {
            record(code, params, verified, true, true, dedupeKey, Result.AMOUNT_MISMATCH, remoteIp);
            confirmationService.markAnomalyInTx(order.getId(), PaymentAnomaly.NOTIFY_AMOUNT_MISMATCH,
                    PaymentOrderTrigger.NOTIFY);
            alertService.alert(PaymentAlertType.NOTIFY_AMOUNT_MISMATCH, order.getOrderNo(),
                    "amountRaw=" + verified.amountRaw() + " expectedFen=" + order.getAmountFen());
            return provider.notificationAck();
        }

        if (order.getStatus() == PaymentOrderStatus.PAID || order.getStatus() == PaymentOrderStatus.FULFILLED) {
            record(code, params, verified, true, true, dedupeKey, Result.APPLIED, remoteIp);
            triggerConfirmation(order.getId());
            return provider.notificationAck();
        }

        record(code, params, verified, true, true, dedupeKey, Result.PENDING_CONFIRM, remoteIp);
        triggerConfirmation(order.getId());
        return provider.notificationAck();
    }

    /** Runs the confirmation query in the background and waits at most notify-confirm-wait. */
    private void triggerConfirmation(Long orderId) {
        CompletableFuture<PaymentConfirmationService.Outcome> future;
        try {
            future = CompletableFuture.supplyAsync(
                    () -> confirmationService.confirmByQuery(orderId, PaymentOrderTrigger.NOTIFY, false),
                    confirmExecutor);
        } catch (RejectedExecutionException ex) {
            log.warn("Payment confirm executor saturated; background jobs will confirm orderId={}", orderId);
            return;
        }
        try {
            future.get(properties.getNotifyConfirmWait().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            log.info("Payment confirmation still running after notify wait orderId={}", orderId);
        } catch (ExecutionException ex) {
            log.warn("Payment confirmation failed after notify orderId={} error={}",
                    orderId, ex.getCause() == null ? ex.toString() : ex.getCause().getClass().getSimpleName());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void record(
            PaymentProviderCode code,
            Map<String, String> params,
            VerifiedNotification verified,
            boolean pidMatch,
            boolean signValid,
            String dedupeKey,
            Result result,
            String remoteIp
    ) {
        PaymentNotificationEntity entity = PaymentNotificationEntity.of(
                code,
                verified != null ? verified.orderNo() : params.get("out_trade_no"),
                verified != null ? verified.providerTradeNo() : params.get("trade_no"),
                verified != null ? verified.tradeStatus() : params.get("trade_status"),
                verified != null ? verified.amountRaw() : params.get("money"),
                verified != null ? verified.channelRaw() : params.get("type"),
                pidMatch,
                signValid,
                YusenSigner.canonicalString(params),
                dedupeKey,
                result,
                remoteIp,
                clock.instant());
        try {
            notificationRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            log.info("Concurrent duplicate payment notification dedupeKey={}", dedupeKey);
        }
    }

    /** SHA-256 over all parameters (including sign) in name order: identical retries share a key. */
    static String dedupeKey(Map<String, String> params) {
        String canonical = new TreeMap<>(params).entrySet().stream()
                .map(e -> e.getKey() + "=" + (e.getValue() == null ? "" : e.getValue()))
                .collect(Collectors.joining("&"));
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
