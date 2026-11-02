package com.aurora.appserver.trial.web;

import jakarta.validation.constraints.NotBlank;

/**
 * Clients may optionally report {@code remainingSecondsCap} (0..259200).
 * Absolute start/end/status/app_id from the client are ignored.
 */
public record DeviceTrialActivateRequest(
        @NotBlank String deviceKey,
        Long remainingSecondsCap
) {
}
