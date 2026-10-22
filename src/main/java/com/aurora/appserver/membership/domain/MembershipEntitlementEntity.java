package com.aurora.appserver.membership.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "membership_entitlements")
@IdClass(MembershipEntitlementEntity.Pk.class)
public class MembershipEntitlementEntity {

    @Id
    @Column(name = "app_id", nullable = false)
    private Long appId;

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "is_lifetime", nullable = false)
    private boolean lifetime;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MembershipEntitlementEntity() {
    }

    public static MembershipEntitlementEntity empty(Long appId, Long userId) {
        MembershipEntitlementEntity entity = new MembershipEntitlementEntity();
        entity.appId = appId;
        entity.userId = userId;
        entity.lifetime = false;
        return entity;
    }

    public void apply(boolean lifetime, Instant startedAt, Instant expiresAt) {
        this.lifetime = lifetime;
        this.startedAt = startedAt;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getAppId() {
        return appId;
    }

    public Long getUserId() {
        return userId;
    }

    public boolean isLifetime() {
        return lifetime;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public static class Pk implements Serializable {
        private Long appId;
        private Long userId;

        public Pk() {
        }

        public Pk(Long appId, Long userId) {
            this.appId = appId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Pk pk)) {
                return false;
            }
            return Objects.equals(appId, pk.appId) && Objects.equals(userId, pk.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(appId, userId);
        }
    }
}
