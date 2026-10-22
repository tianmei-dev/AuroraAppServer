package com.aurora.appserver.auth.security;

/**
 * Authenticated session principal. Carries server-trusted app/user ids only.
 */
public record SessionPrincipal(
        Long sessionId,
        Long appId,
        String appCode,
        Long userId,
        String tokenHash
) {
}
