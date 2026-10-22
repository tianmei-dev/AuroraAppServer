package com.aurora.appserver.trial.web;

import jakarta.validation.constraints.NotBlank;

/**
 * Only deviceKey is accepted. Clients cannot supply start/end/status/app_id.
 */
public record DeviceTrialActivateRequest(
        @NotBlank String deviceKey
) {
}
