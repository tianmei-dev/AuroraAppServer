package com.aurora.appserver.membership.domain;

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
import java.time.Instant;

@Entity
@Table(name = "membership_grants")
public class MembershipGrantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_id", nullable = false)
    private Long appId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_code", nullable = false, length = 32)
    private PlanCode planCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private GrantSourceType sourceType;

    @Column(name = "source_ref", nullable = false, length = 128)
    private String sourceRef;

    @Column(name = "order_id", length = 128)
    private String orderId;

    @Column(name = "duration_days")
    private Integer durationDays;

    @Column(name = "is_lifetime", nullable = false)
    private boolean lifetime;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason", length = 255)
    private String revokeReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MembershipGrantEntity() {
    }

    public static MembershipGrantEntity create(
            Long appId,
            Long userId,
            MembershipPlanEntity plan,
            GrantSourceType sourceType,
            String sourceRef,
            String orderId,
            Instant grantedAt
    ) {
        MembershipGrantEntity grant = new MembershipGrantEntity();
        grant.appId = appId;
        grant.userId = userId;
        grant.planId = plan.getId();
        grant.planCode = plan.getPlanCode();
        grant.sourceType = sourceType;
        grant.sourceRef = sourceRef;
        grant.orderId = orderId;
        grant.durationDays = plan.getDurationDays();
        grant.lifetime = plan.isLifetime();
        grant.grantedAt = grantedAt;
        return grant;
    }

    /** Builds a detached grant for entitlement replay unit tests (no persistence). */
    public static MembershipGrantEntity forComputation(
            long id,
            PlanCode planCode,
            Integer durationDays,
            boolean lifetime,
            Instant grantedAt
    ) {
        MembershipGrantEntity grant = new MembershipGrantEntity();
        grant.id = id;
        grant.appId = 1L;
        grant.userId = 1L;
        grant.planId = 1L;
        grant.planCode = planCode;
        grant.sourceType = GrantSourceType.TEST;
        grant.sourceRef = "compute-" + id;
        grant.durationDays = durationDays;
        grant.lifetime = lifetime;
        grant.grantedAt = grantedAt;
        return grant;
    }

    public void revoke(Instant revokedAt, String reason) {
        if (this.revokedAt == null) {
            this.revokedAt = revokedAt;
            this.revokeReason = reason;
        }
    }

    public boolean isRevoked() {
        return revokedAt != null;
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

    public Long getId() {
        return id;
    }

    public Long getAppId() {
        return appId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getPlanId() {
        return planId;
    }

    public PlanCode getPlanCode() {
        return planCode;
    }

    public GrantSourceType getSourceType() {
        return sourceType;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public String getOrderId() {
        return orderId;
    }

    public Integer getDurationDays() {
        return durationDays;
    }

    public boolean isLifetime() {
        return lifetime;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevokeReason() {
        return revokeReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
