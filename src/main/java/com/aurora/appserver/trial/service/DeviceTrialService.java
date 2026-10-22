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

@Service
public class DeviceTrialService {

    private static final Logger log = LoggerFactory.getLogger(DeviceTrialService.class);
    private static final int DEVICE_KEY_MIN = 8;
    private static final int DEVICE_KEY_MAX = 128;
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

    public TrialView activate(String appCodeHeader, String deviceKey, String clientIp) {
        AppContext app = appRegistryService.requireActiveApp(appCodeHeader);
        requireHmacConfigured();
        String normalizedKey = validateDeviceKey(deviceKey);

        String rateKey = "trial:" + app.appCode() + ":" + (StringUtils.hasText(clientIp) ? clientIp : "unknown");
        rateLimiter.checkOrThrow(rateKey);

        String deviceKeyHash = deviceKeyHasher.hash(app.appCode(), normalizedKey);
        DeviceTrialEntity trial = resolveTrial(app.appId(), deviceKeyHash);
        return toView(trial);
    }

    DeviceTrialEntity resolveTrial(Long appId, String deviceKeyHash) {
        return deviceTrialRepository.findByAppIdAndDeviceKeyHash(appId, deviceKeyHash)
                .orElseGet(() -> createHandlingRace(appId, deviceKeyHash));
    }

    private DeviceTrialEntity createHandlingRace(Long appId, String deviceKeyHash) {
        try {
            return self.createInNewTx(appId, deviceKeyHash);
        } catch (DataAccessException ex) {
            log.info("Concurrent device trial create detected; recovering existing record");
            return deviceTrialRepository.findByAppIdAndDeviceKeyHash(appId, deviceKeyHash)
                    .orElseThrow(() -> new BusinessException(ApiErrorCode.INTERNAL_ERROR));
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeviceTrialEntity createInNewTx(Long appId, String deviceKeyHash) {
        return deviceTrialRepository.findByAppIdAndDeviceKeyHash(appId, deviceKeyHash)
                .orElseGet(() -> {
                    Instant now = clock.instant();
                    Duration duration = properties.getTrial().getDuration();
                    Instant expires = now.plus(duration);
                    return deviceTrialRepository.saveAndFlush(
                            DeviceTrialEntity.create(appId, deviceKeyHash, now, now, expires));
                });
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
