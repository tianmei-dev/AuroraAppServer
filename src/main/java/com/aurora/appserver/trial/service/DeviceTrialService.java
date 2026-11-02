package com.aurora.appserver.trial.service;

import com.aurora.appserver.app.context.AppContext;
import com.aurora.appserver.app.service.AppRegistryService;
import com.aurora.appserver.auth.config.AuroraAuthProperties;
import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.exception.BusinessException;
import com.aurora.appserver.trial.domain.DeviceTrialEntity;
import com.aurora.appserver.trial.repository.DeviceTrialRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Device 72h trial. S3.2: optional {@code remainingSecondsCap} may only keep or shorten
 * {@code trial_expires_at}; never extend. Success responses always reflect persisted state.
 */
@Service
public class DeviceTrialService {

    private static final Logger log = LoggerFactory.getLogger(DeviceTrialService.class);
    private static final int DEVICE_KEY_MIN = 8;
    private static final int DEVICE_KEY_MAX = 128;
    private static final long CAP_MIN = 0L;
    private static final long CAP_MAX = 259_200L; // 72h
    private static final Pattern DEVICE_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._\\-:]+$");

    private final AppRegistryService appRegistryService;
    private final DeviceTrialRepository deviceTrialRepository;
    private final DeviceKeyHasher deviceKeyHasher;
    private final InMemoryRateLimiter rateLimiter;
    private final AuroraAuthProperties properties;
    private final Clock clock;
    private final DeviceTrialService self;

    public DeviceTrialService(
            AppRegistryService appRegistryService,
            DeviceTrialRepository deviceTrialRepository,
            DeviceKeyHasher deviceKeyHasher,
            InMemoryRateLimiter rateLimiter,
            AuroraAuthProperties properties,
            Clock clock,
            @org.springframework.context.annotation.Lazy DeviceTrialService self
    ) {
        this.appRegistryService = appRegistryService;
        this.deviceTrialRepository = deviceTrialRepository;
        this.deviceKeyHasher = deviceKeyHasher;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.clock = clock;
        this.self = self;
    }

    public record TrialView(
            String status,
            Instant trialStartedAt,
            Instant trialExpiresAt,
            Instant serverTime,
            long remainingSeconds
    ) {
    }

    public TrialView activate(String appCodeHeader, String deviceKey, Long remainingSecondsCap, String clientIp) {
        AppContext app = appRegistryService.requireActiveApp(appCodeHeader);
        requireHmacConfigured();
        String normalizedKey = validateDeviceKey(deviceKey);
        Long cap = normalizeCap(remainingSecondsCap);

        String rateKey = "trial:" + app.appCode() + ":" + (StringUtils.hasText(clientIp) ? clientIp : "unknown");
        rateLimiter.checkOrThrow(rateKey);

        String deviceKeyHash = deviceKeyHasher.hash(app.appCode(), normalizedKey);
        DeviceTrialEntity trial = resolveTrial(app.appId(), deviceKeyHash, cap);
        return toView(trial);
    }

    /**
     * Ensure a row exists (create race-safe), then always re-read — and if a cap is present,
     * atomically LEAST-tighten. Losing concurrent inserts must still apply their stricter cap.
     */
    DeviceTrialEntity resolveTrial(Long appId, String deviceKeyHash, Long remainingSecondsCap) {
        if (deviceTrialRepository.findByAppIdAndDeviceKeyHash(appId, deviceKeyHash).isEmpty()) {
            try {
                self.createInNewTx(appId, deviceKeyHash, remainingSecondsCap);
            } catch (DataAccessException ex) {
                log.info("Concurrent device trial create detected; will apply cap on existing row if present");
            }
        }
        return applyCapIfPresent(appId, deviceKeyHash, remainingSecondsCap);
    }

    /**
     * First activation: final expires (including optional cap) is written in the same transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeviceTrialEntity createInNewTx(Long appId, String deviceKeyHash, Long remainingSecondsCap) {
        return deviceTrialRepository.findByAppIdAndDeviceKeyHash(appId, deviceKeyHash)
                .orElseGet(() -> {
                    Instant now = clock.instant();
                    Instant expires = computeFirstExpires(now, remainingSecondsCap);
                    return deviceTrialRepository.saveAndFlush(
                            DeviceTrialEntity.create(appId, deviceKeyHash, now, now, expires));
                });
    }

    private DeviceTrialEntity applyCapIfPresent(Long appId, String deviceKeyHash, Long remainingSecondsCap) {
        if (remainingSecondsCap != null) {
            Instant candidate = clock.instant().plusSeconds(remainingSecondsCap);
            self.tightenInNewTx(appId, deviceKeyHash, candidate);
        }
        return deviceTrialRepository.findByAppIdAndDeviceKeyHash(appId, deviceKeyHash)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.INTERNAL_ERROR));
    }

    /**
     * Atomic LEAST update — concurrent / out-of-order caps cannot extend the stored expiry.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void tightenInNewTx(Long appId, String deviceKeyHash, Instant candidateExpiresAt) {
        // MySQL may report 0 "changed" rows when LEAST leaves the value unchanged; do not treat as failure.
        deviceTrialRepository.tightenExpiresAtIfEarlier(appId, deviceKeyHash, candidateExpiresAt);
    }

    Instant computeFirstExpires(Instant now, Long remainingSecondsCap) {
        Instant full = now.plus(properties.getTrial().getDuration());
        if (remainingSecondsCap == null) {
            return full;
        }
        Instant capped = now.plusSeconds(remainingSecondsCap);
        return capped.isBefore(full) ? capped : full;
    }

    private TrialView toView(DeviceTrialEntity trial) {
        Instant serverTime = clock.instant();
        boolean active = serverTime.isBefore(trial.getTrialExpiresAt());
        long remaining = active
                ? Math.max(0, Duration.between(serverTime, trial.getTrialExpiresAt()).getSeconds())
                : 0L;
        return new TrialView(
                active ? "ACTIVE" : "EXPIRED",
                trial.getTrialStartedAt(),
                trial.getTrialExpiresAt(),
                serverTime,
                remaining
        );
    }

    private void requireHmacConfigured() {
        if (!properties.getTrial().isHmacConfigured()) {
            throw new BusinessException(ApiErrorCode.TRIAL_CONFIG_MISSING);
        }
    }

    static Long normalizeCap(Long remainingSecondsCap) {
        if (remainingSecondsCap == null) {
            return null;
        }
        if (remainingSecondsCap < CAP_MIN || remainingSecondsCap > CAP_MAX) {
            throw new BusinessException(ApiErrorCode.TRIAL_CAP_INVALID);
        }
        return remainingSecondsCap;
    }

    static String validateDeviceKey(String deviceKey) {
        if (!StringUtils.hasText(deviceKey)) {
            throw new BusinessException(ApiErrorCode.DEVICE_KEY_INVALID);
        }
        String trimmed = deviceKey.trim();
        if (trimmed.length() < DEVICE_KEY_MIN || trimmed.length() > DEVICE_KEY_MAX) {
            throw new BusinessException(ApiErrorCode.DEVICE_KEY_INVALID);
        }
        if (!DEVICE_KEY_PATTERN.matcher(trimmed).matches()) {
            throw new BusinessException(ApiErrorCode.DEVICE_KEY_INVALID);
        }
        return trimmed;
    }
}
