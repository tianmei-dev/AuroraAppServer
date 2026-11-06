package com.aurora.appserver.payment.domain;

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

@Entity
@Table(name = "payment_order_events")
public class PaymentOrderEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32)
    private PaymentOrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 32)
    private PaymentOrderStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_source", nullable = false, length = 32)
    private PaymentOrderTrigger triggerSource;

    @Column(name = "detail", length = 255)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentOrderEventEntity() {
    }

    public static PaymentOrderEventEntity of(
            Long orderId,
            PaymentOrderStatus fromStatus,
            PaymentOrderStatus toStatus,
            PaymentOrderTrigger triggerSource,
            String detail
    ) {
        PaymentOrderEventEntity event = new PaymentOrderEventEntity();
        event.orderId = orderId;
        event.fromStatus = fromStatus;
        event.toStatus = toStatus;
        event.triggerSource = triggerSource;
        event.detail = detail == null || detail.length() <= 255 ? detail : detail.substring(0, 255);
        return event;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public PaymentOrderStatus getFromStatus() {
        return fromStatus;
    }

    public PaymentOrderStatus getToStatus() {
        return toStatus;
    }

    public PaymentOrderTrigger getTriggerSource() {
        return triggerSource;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
