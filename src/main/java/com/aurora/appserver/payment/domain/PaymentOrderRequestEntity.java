package com.aurora.appserver.payment.domain;

import com.aurora.appserver.membership.domain.PlanCode;
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

/** Binds one clientRequestId to exactly one order (new or reused). */
@Entity
@Table(name = "payment_order_requests")
public class PaymentOrderRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_id", nullable = false)
    private Long appId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "client_request_id", nullable = false, length = 64)
    private String clientRequestId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_code", nullable = false, length = 32)
    private PlanCode planCode;

    @Column(name = "reused", nullable = false)
    private boolean reused;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentOrderRequestEntity() {
    }

    public static PaymentOrderRequestEntity create(
            Long appId,
            Long userId,
            String clientRequestId,
            Long orderId,
            PlanCode planCode,
            boolean reused
    ) {
        PaymentOrderRequestEntity request = new PaymentOrderRequestEntity();
        request.appId = appId;
        request.userId = userId;
        request.clientRequestId = clientRequestId;
        request.orderId = orderId;
        request.planCode = planCode;
        request.reused = reused;
        return request;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getAppId() {
        return appId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public PlanCode getPlanCode() {
        return planCode;
    }

    public boolean isReused() {
        return reused;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
