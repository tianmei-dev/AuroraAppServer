package com.aurora.appserver.trial.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "device_trials")
public class DeviceTrialEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_id", nullable = false)
    private Long appId;

    @Column(name = "device_key_hash", nullable = false, length = 128)
    private String deviceKeyHash;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "trial_started_at", nullable = false)
    private Instant trialStartedAt;

    @Column(name = "trial_expires_at", nullable = false)
    private Instant trialExpiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DeviceTrialEntity() {
    }

    public static DeviceTrialEntity create(
            Long appId,
            String deviceKeyHash,
            Instant firstSeenAt,
            Instant trialStartedAt,
            Instant trialExpiresAt
    ) {
        DeviceTrialEntity trial = new DeviceTrialEntity();
        trial.appId = appId;
        trial.deviceKeyHash = deviceKeyHash;
        trial.firstSeenAt = firstSeenAt;
        trial.trialStartedAt = trialStartedAt;
        trial.trialExpiresAt = trialExpiresAt;
        return trial;
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

    public String getDeviceKeyHash() {
        return deviceKeyHash;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getTrialStartedAt() {
        return trialStartedAt;
    }

    public Instant getTrialExpiresAt() {
        return trialExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
