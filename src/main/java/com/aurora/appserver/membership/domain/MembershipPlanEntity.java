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
@Table(name = "membership_plans")
public class MembershipPlanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_id", nullable = false)
    private Long appId;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_code", nullable = false, length = 32)
    private PlanCode planCode;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "price_fen", nullable = false)
    private int priceFen;

    @Column(name = "duration_days")
    private Integer durationDays;

    @Column(name = "is_lifetime", nullable = false)
    private boolean lifetime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PlanStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MembershipPlanEntity() {
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

    public PlanCode getPlanCode() {
        return planCode;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getPriceFen() {
        return priceFen;
    }

    public Integer getDurationDays() {
        return durationDays;
    }

    public boolean isLifetime() {
        return lifetime;
    }

    public PlanStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isActive() {
        return status == PlanStatus.ACTIVE;
    }
}
