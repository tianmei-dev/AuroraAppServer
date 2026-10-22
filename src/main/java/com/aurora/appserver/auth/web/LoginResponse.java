package com.aurora.appserver.auth.web;

import java.time.Instant;

public record LoginResponse(
        String accessToken,
        String tokenType,
        Instant expiresAt,
        Long userId,
        String appCode
) {
}
