package com.aurora.appserver.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Inbound notification evidence. Stores no merchant key; the raw signature is not kept. */
@Entity
@Table(name = "payment_notifications")
public class PaymentNotificationEntity {

    public enum Source {
        NOTIFY,
        RETURN
    }

    public enum Result {
        APPLIED,
        DUPLICATE,
        IGNORED_STATUS,
        REJECTED_SIGN,
        REJECTED_PID,
        ORDER_NOT_FOUND,
        AMOUNT_MISMATCH,
        PENDING_CONFIRM
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private PaymentProviderCode provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private Source source;

    @Column(name = "order_no", length = 64)
    private String orderNo;

    @Column(name = "provider_trade_no", length = 64)
    private String providerTradeNo;

    @Column(name = "trade_status", length = 32)
    private String tradeStatus;

    @Column(name = "amount_raw", length = 32)
    private String amountRaw;

    @Column(name = "channel_raw", length = 32)
    private String channelRaw;

    @Column(name = "pid_match", nullable = false)
    private boolean pidMatch;

    @Column(name = "sign_valid", nullable = false)
    private boolean signValid;

    @Column(name = "params_sanitized", length = 2000)
    private String paramsSanitized;

    @Column(name = "dedupe_key", nullable = false, length = 64)
    private String dedupeKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "process_result", nullable = false, length = 32)
    private Result processResult;

    @Column(name = "remote_ip", length = 64)
    private String remoteIp;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected PaymentNotificationEntity() {
    }

    public static PaymentNotificationEntity of(
            PaymentProviderCode provider,
            String orderNo,
            String providerTradeNo,
            String tradeStatus,
            String amountRaw,
            String channelRaw,
            boolean pidMatch,
            boolean signValid,
            String paramsSanitized,
            String dedupeKey,
            Result result,
            String remoteIp,
            Instant receivedAt
    ) {
        PaymentNotificationEntity n = new PaymentNotificationEntity();
        n.provider = provider;
        n.source = Source.NOTIFY;
        n.orderNo = cut(orderNo, 64);
        n.providerTradeNo = cut(providerTradeNo, 64);
        n.tradeStatus = cut(tradeStatus, 32);
        n.amountRaw = cut(amountRaw, 32);
        n.channelRaw = cut(channelRaw, 32);
        n.pidMatch = pidMatch;
        n.signValid = signValid;
        n.paramsSanitized = cut(paramsSanitized, 2000);
        n.dedupeKey = dedupeKey;
        n.processResult = result;
        n.remoteIp = cut(remoteIp, 64);
        n.receivedAt = receivedAt;
        n.processedAt = result == Result.PENDING_CONFIRM ? null : receivedAt;
        return n;
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() {
        return id;
    }

    public PaymentProviderCode getProvider() {
        return provider;
    }

    public Source getSource() {
        return source;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public String getProviderTradeNo() {
        return providerTradeNo;
    }

    public String getTradeStatus() {
        return tradeStatus;
    }

    public String getAmountRaw() {
        return amountRaw;
    }

    public String getChannelRaw() {
        return channelRaw;
    }

    public boolean isPidMatch() {
        return pidMatch;
    }

    public boolean isSignValid() {
        return signValid;
    }

    public String getParamsSanitized() {
        return paramsSanitized;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public Result getProcessResult() {
        return processResult;
    }

    public String getRemoteIp() {
        return remoteIp;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
