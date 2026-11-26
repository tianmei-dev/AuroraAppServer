package com.aurora.appserver.auth.web;

import java.time.Instant;

public record AdminLoginResponse(
        String accessToken,
        String tokenType,
        Instant expiresAt,
        String username,
        String appCode
) {
}
