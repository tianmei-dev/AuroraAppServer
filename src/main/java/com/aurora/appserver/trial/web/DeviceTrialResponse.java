package com.aurora.appserver.trial.web;

import java.time.Instant;

public record DeviceTrialResponse(
        String status,
        Instant trialStartedAt,
        Instant trialExpiresAt,
        Instant serverTime,
        long remainingSeconds
) {
}
