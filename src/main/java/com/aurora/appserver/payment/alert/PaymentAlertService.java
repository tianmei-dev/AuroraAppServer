package com.aurora.appserver.payment.alert;

import com.aurora.appserver.payment.config.PaymentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Payment anomaly alerts: always one structured log line
 * {@code PAYMENT_ALERT type=.. orderNo=.. at=.. env=.. detail=..}; optionally an async webhook push.
 *
 * <p>Never throws and never blocks the caller, so alerting can't affect payment confirmation or
 * membership fulfilment. Callers must pass only non-secret details (codes, counts) — never keys,
 * signed links or query URLs.
 */
@Service
public class PaymentAlertService {

    private static final Logger log = LoggerFactory.getLogger(PaymentAlertService.class);
    private static final int DETAIL_MAX = 200;

    private final PaymentAlertSender sender;
    private final PaymentProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, Instant> lastPushed = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;

    public PaymentAlertService(
            PaymentAlertSender sender,
            PaymentProperties properties,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.sender = sender;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.executor = new ThreadPoolExecutor(
                1, 1, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "pay-alert");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    public void alert(PaymentAlertType type, String orderNo, String detail) {
        try {
            Instant now = clock.instant();
            String safeOrderNo = orderNo == null ? "-" : truncate(orderNo, 64);
            String safeDetail = detail == null ? "-" : truncate(detail, DETAIL_MAX);
            String environment = String.valueOf(properties.getEnvironment());
            if (type.severity() == PaymentAlertType.Severity.ERROR) {
                log.error("PAYMENT_ALERT type={} orderNo={} at={} env={} detail={}",
                        type, safeOrderNo, now, environment, safeDetail);
            } else {
                log.warn("PAYMENT_ALERT type={} orderNo={} at={} env={} detail={}",
                        type, safeOrderNo, now, environment, safeDetail);
            }
            if (!sender.isEnabled() || suppressed(type, safeOrderNo, now)) {
                return;
            }
            String payload = payload(type, safeOrderNo, now, environment, safeDetail);
            executor.execute(() -> push(type, safeOrderNo, payload));
        } catch (RejectedExecutionException ex) {
            log.warn("PAYMENT_ALERT push dropped (queue full) type={} orderNo={}", type, orderNo);
        } catch (RuntimeException ex) {
            log.warn("PAYMENT_ALERT handling failed type={} error={}", type, ex.getClass().getSimpleName());
        }
    }

    private void push(PaymentAlertType type, String orderNo, String payload) {
        try {
            sender.send(payload);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.warn("PAYMENT_ALERT push failed type={} orderNo={} error={}",
                    type, orderNo, ex.getClass().getSimpleName());
        }
    }

    private boolean suppressed(PaymentAlertType type, String orderNo, Instant now) {
        String key = type + "|" + orderNo;
        Instant previous = lastPushed.get(key);
        if (previous != null && previous.plus(properties.getAlert().getDedupeWindow()).isAfter(now)) {
            return true;
        }
        lastPushed.put(key, now);
        if (lastPushed.size() > 10_000) {
            lastPushed.clear();
        }
        return false;
    }

    String payload(PaymentAlertType type, String orderNo, Instant at, String environment, String detail) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("source", "aurora-app-server");
        node.put("category", "payment");
        node.put("type", type.name());
        node.put("severity", type.severity().name());
        node.put("orderNo", orderNo);
        node.put("occurredAt", at.toString());
        node.put("environment", environment);
        node.put("detail", detail);
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
