package com.aurora.appserver.auth.web;

import jakarta.validation.constraints.NotBlank;

public record WechatLoginRequest(
        @NotBlank String code
) {
}
