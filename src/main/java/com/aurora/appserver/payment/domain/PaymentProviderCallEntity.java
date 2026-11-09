package com.aurora.appserver.payment.domain;

import com.aurora.appserver.payment.provider.PaymentQueryResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** Outbound call journal. Never stores request URLs or merchant keys. */
@Entity
@Table(name = "payment_provider_calls")
public class PaymentProviderCallEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private PaymentProviderCode provider;

    @Column(name = "operation", nullable = false, length = 16)
    private String operation;

    @Column(name = "order_no", nullable = false, length = 64)
    private String orderNo;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "provider_code", length = 16)
    private String providerCode;

    @Column(name = "provider_msg", length = 255)
    private String providerMsg;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 16)
    private PaymentQueryResult.Outcome result;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentProviderCallEntity() {
    }

    public static PaymentProviderCallEntity query(
            PaymentProviderCode provider, String orderNo, PaymentQueryResult result, long durationMs) {
        PaymentProviderCallEntity call = new PaymentProviderCallEntity();
        call.provider = provider;
        call.operation = "QUERY";
        call.orderNo = orderNo;
        call.httpStatus = result.httpStatus();
        call.providerCode = cut(result.providerCode(), 16);
        call.providerMsg = cut(result.providerMsg(), 255);
        call.result = result.outcome();
        call.durationMs = (int) Math.min(Integer.MAX_VALUE, Math.max(0, durationMs));
        return call;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
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

    public String getOperation() {
        return operation;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public String getProviderCode() {
        return providerCode;
    }

    public String getProviderMsg() {
        return providerMsg;
    }

    public PaymentQueryResult.Outcome getResult() {
        return result;
    }

    public Integer getDurationMs() {
        return durationMs;
    }
}
