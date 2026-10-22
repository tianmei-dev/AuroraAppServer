package com.aurora.appserver.trial.service;

import com.aurora.appserver.auth.config.AuroraAuthProperties;
import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Derives device_key_hash = HMAC-SHA256(secret, appCode || 0x00 || deviceKey).
 * Raw deviceKey is never persisted.
 */
@Component
public class DeviceKeyHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final AuroraAuthProperties properties;

    public DeviceKeyHasher(AuroraAuthProperties properties) {
        this.properties = properties;
    }

    public String hash(String appCode, String deviceKey) {
        String secret = properties.getTrial().getHmacSecret();
        if (secret == null || secret.isBlank()) {
            throw new BusinessException(ApiErrorCode.TRIAL_CONFIG_MISSING);
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            mac.update(appCode.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(deviceKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ApiErrorCode.INTERNAL_ERROR);
        }
    }
}
