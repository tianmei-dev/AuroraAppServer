package com.aurora.appserver.trial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aurora.appserver.auth.config.AuroraAuthProperties;
import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.exception.BusinessException;
import com.aurora.appserver.trial.service.DeviceKeyHasher;
import org.junit.jupiter.api.Test;

class DeviceKeyHasherTest {

    @Test
    void missingSecretThrowsTrialConfigMissing() {
        AuroraAuthProperties properties = new AuroraAuthProperties();
        properties.getTrial().setHmacSecret(" ");
        DeviceKeyHasher hasher = new DeviceKeyHasher(properties);

        assertThatThrownBy(() -> hasher.hash("calculator_vault", "device-key-1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ApiErrorCode.TRIAL_CONFIG_MISSING));
    }
}
