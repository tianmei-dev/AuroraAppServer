package com.aurora.appserver.auth.security;

/**
 * Authenticated admin session. Not a WeChat user — never use for payment / membership grant APIs.
 */
public record AdminPrincipal(
        Long sessionId,
        Long appId,
        String appCode,
        String username,
        String tokenHash
) {
}
