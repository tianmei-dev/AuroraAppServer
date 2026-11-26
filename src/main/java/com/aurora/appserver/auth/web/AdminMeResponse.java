package com.aurora.appserver.auth.web;

public record AdminMeResponse(
        String username,
        String appCode,
        String role
) {
}
