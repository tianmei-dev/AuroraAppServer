package com.aurora.appserver.auth.web;

public record MeResponse(
        Long userId,
        String appCode,
        String status
) {
}
