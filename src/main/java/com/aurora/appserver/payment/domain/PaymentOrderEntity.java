package com.aurora.appserver.payment.domain;

import com.aurora.appserver.membership.domain.MembershipPlanEntity;
import com.aurora.appserver.membership.domain.PlanCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Payment order with an immutable plan/price snapshot taken at creation time.
 * Amount, duration and lifetime flag never come from the client.
 */
@Entity
@Table(name = "payment_orders")
public class PaymentOrderEntity {

    public static final String CURRENCY_CNY = "CNY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_id", nullable = false)
    private Long appId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "order_no", nullable = false, length = 32)
    private String orderNo;

    @Column(name = "client_request_id", nullable = false, length = 64)
    private String clientRequestId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_code", nullable = false, length = 32)
    private PlanCode planCode;

    @Column(name = "plan_display_name", nullable = false, length = 128)
    private String planDisplayName;

    @Column(name = "amount_fen", nullable = false)
    private int amountFen;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "duration_days")
    private Integer durationDays;

    @Column(name = "is_lifetime", nullable = false)
    private boolean lifetime;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private PaymentProviderCode provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "environment", nullable = false, length = 16)
    private PaymentEnvironment environment;

    @Column(name = "merchant_pid", length = 64)
    private String merchantPid;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentOrderStatus status;

    @Column(name = "provider_trade_no", length = 64)
    private String providerTradeNo;

    @Column(name = "upstream_trade_no", length = 64)
    private String upstreamTradeNo;

    @Column(name = "paid_channel_raw", length = 32)
    private String paidChannelRaw;

    @Column(name = "paid_amount_fen")
    private Integer paidAmountFen;

    @Column(name = "provider_paid_at_raw", length = 32)
    private String providerPaidAtRaw;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    @Column(name = "late_paid", nullable = false)
    private boolean latePaid;

    @Column(name = "anomaly_code", length = 64)
    private String anomalyCode;

    @Column(name = "membership_grant_id")
    private Long membershipGrantId;

    @Column(name = "fulfill_attempts", nullable = false)
    private int fulfillAttempts;

    @Column(name = "next_fulfill_at")
    private Instant nextFulfillAt;

    @Column(name = "last_fulfill_error", length = 255)
    private String lastFulfillError;

    @Column(name = "query_attempts", nullable = false)
    private int queryAttempts;

    @Column(name = "last_query_at")
    private Instant lastQueryAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentOrderEntity() {
    }

    public static PaymentOrderEntity createPending(
            Long appId,
            Long userId,
            String orderNo,
            String clientRequestId,
            MembershipPlanEntity plan,
            PaymentProviderCode provider,
            PaymentEnvironment environment,
            String merchantPid,
            Instant expiresAt
    ) {
        PaymentOrderEntity order = new PaymentOrderEntity();
        order.appId = appId;
        order.userId = userId;
        order.orderNo = orderNo;
        order.clientRequestId = clientRequestId;
        order.planId = plan.getId();
        order.planCode = plan.getPlanCode();
        order.planDisplayName = plan.getDisplayName();
        order.amountFen = plan.getPriceFen();
        order.currency = CURRENCY_CNY;
        order.durationDays = plan.getDurationDays();
        order.lifetime = plan.isLifetime();
        order.provider = provider;
        order.environment = environment;
        order.merchantPid = merchantPid;
        order.status = PaymentOrderStatus.PENDING_PAYMENT;
        order.expiresAt = expiresAt;
        return order;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Status as presented to clients. A pending order past its expiry is shown as EXPIRED even
     * before the expiry job persists it (persisting requires a final provider query).
     */
    public PaymentOrderStatus effectiveStatus(Instant now) {
        if (status == PaymentOrderStatus.PENDING_PAYMENT && !now.isBefore(expiresAt)) {
            return PaymentOrderStatus.EXPIRED;
        }
        return status;
    }

    public boolean isPayable(Instant now) {
        return effectiveStatus(now) == PaymentOrderStatus.PENDING_PAYMENT;
    }

    // ------------------------------------------------------------------
    // State transitions. Callers must hold the row lock (SELECT ... FOR UPDATE).
    // ------------------------------------------------------------------

    /** PENDING_PAYMENT or EXPIRED -> PAID. Returns false if already PAID/FULFILLED. */
    public boolean markPaid(
            Instant now,
            String providerTradeNo,
            String upstreamTradeNo,
            String paidChannelRaw,
            int paidAmountFen,
            String providerPaidAtRaw
    ) {
        if (status != PaymentOrderStatus.PENDING_PAYMENT && status != PaymentOrderStatus.EXPIRED) {
            return false;
        }
        // Late = confirmed only after we had already expired the order locally.
        boolean late = status == PaymentOrderStatus.EXPIRED;
        this.status = PaymentOrderStatus.PAID;
        this.paidAt = now;
        if (providerTradeNo != null && !providerTradeNo.isBlank()) {
            this.providerTradeNo = truncate(providerTradeNo, 64);
        }
        this.upstreamTradeNo = truncate(upstreamTradeNo, 64);
        this.paidChannelRaw = truncate(paidChannelRaw, 32);
        this.paidAmountFen = paidAmountFen;
        this.providerPaidAtRaw = truncate(providerPaidAtRaw, 32);
        this.nextFulfillAt = now;
        if (late) {
            this.latePaid = true;
            this.anomalyCode = PaymentAnomaly.LATE_PAYMENT.name();
        }
        return true;
    }

    /** PENDING_PAYMENT -> EXPIRED. Only after a provider query (or inconclusive grace) says unpaid. */
    public boolean markExpired(Instant now) {
        if (status != PaymentOrderStatus.PENDING_PAYMENT || now.isBefore(expiresAt)) {
            return false;
        }
        this.status = PaymentOrderStatus.EXPIRED;
        this.expiredAt = now;
        return true;
    }

    /** PAID -> FULFILLED. */
    public boolean markFulfilled(Instant now, Long grantId) {
        if (status != PaymentOrderStatus.PAID) {
            return false;
        }
        this.status = PaymentOrderStatus.FULFILLED;
        this.fulfilledAt = now;
        this.membershipGrantId = grantId;
        this.nextFulfillAt = null;
        this.lastFulfillError = null;
        return true;
    }

    /** Claims a fulfillment attempt for {@code lease}; false if not PAID or another worker holds it. */
    public boolean claimFulfillment(Instant now, java.time.Duration lease) {
        if (status != PaymentOrderStatus.PAID) {
            return false;
        }
        if (nextFulfillAt != null && nextFulfillAt.isAfter(now)) {
            return false;
        }
        this.nextFulfillAt = now.plus(lease);
        this.fulfillAttempts++;
        return true;
    }

    public void recordFulfillFailure(Instant retryAt, String error) {
        if (status == PaymentOrderStatus.PAID) {
            this.nextFulfillAt = retryAt;
            this.lastFulfillError = truncate(error, 255);
        }
    }

    /** Claims a provider query slot; false when queried more recently than {@code minInterval}. */
    public boolean claimQuery(Instant now, java.time.Duration minInterval) {
        if (lastQueryAt != null && lastQueryAt.plus(minInterval).isAfter(now)) {
            return false;
        }
        recordQueryAttempt(now);
        return true;
    }

    public void recordQueryAttempt(Instant now) {
        this.lastQueryAt = now;
        this.queryAttempts++;
    }

    public void markAnomaly(PaymentAnomaly anomaly) {
        this.anomalyCode = anomaly.name();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
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

    public String getOrderNo() {
        return orderNo;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public Long getPlanId() {
        return planId;
    }

    public PlanCode getPlanCode() {
        return planCode;
    }

    public String getPlanDisplayName() {
        return planDisplayName;
    }

    public int getAmountFen() {
        return amountFen;
    }

    public String getCurrency() {
        return currency;
    }

    public Integer getDurationDays() {
        return durationDays;
    }

    public boolean isLifetime() {
        return lifetime;
    }

    public PaymentProviderCode getProvider() {
        return provider;
    }

    public PaymentEnvironment getEnvironment() {
        return environment;
    }

    public String getMerchantPid() {
        return merchantPid;
    }

    public PaymentOrderStatus getStatus() {
        return status;
    }

    public String getProviderTradeNo() {
        return providerTradeNo;
    }

    public String getUpstreamTradeNo() {
        return upstreamTradeNo;
    }

    public String getPaidChannelRaw() {
        return paidChannelRaw;
    }

    public Integer getPaidAmountFen() {
        return paidAmountFen;
    }

    public String getProviderPaidAtRaw() {
        return providerPaidAtRaw;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getFulfilledAt() {
        return fulfilledAt;
    }

    public Instant getExpiredAt() {
        return expiredAt;
    }

    public boolean isLatePaid() {
        return latePaid;
    }

    public String getAnomalyCode() {
        return anomalyCode;
    }

    public Long getMembershipGrantId() {
        return membershipGrantId;
    }

    public int getFulfillAttempts() {
        return fulfillAttempts;
    }

    public Instant getNextFulfillAt() {
        return nextFulfillAt;
    }

    public String getLastFulfillError() {
        return lastFulfillError;
    }

    public int getQueryAttempts() {
        return queryAttempts;
    }

    public Instant getLastQueryAt() {
        return lastQueryAt;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
