package com.aurora.appserver.auth.web;

import com.aurora.appserver.auth.domain.UserEntity;
import com.aurora.appserver.auth.domain.UserStatus;
import com.aurora.appserver.auth.repository.UserRepository;
import com.aurora.appserver.auth.security.SessionPrincipal;
import com.aurora.appserver.auth.service.AuthSessionService;
import com.aurora.appserver.auth.service.WechatLoginService;
import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.api.ApiResponse;
import com.aurora.appserver.common.exception.BusinessException;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public static final String APP_CODE_HEADER = "X-App-Code";

    private final WechatLoginService wechatLoginService;
    private final AuthSessionService authSessionService;
    private final UserRepository userRepository;

    public AuthController(
            WechatLoginService wechatLoginService,
            AuthSessionService authSessionService,
            UserRepository userRepository
    ) {
        this.wechatLoginService = wechatLoginService;
        this.authSessionService = authSessionService;
        this.userRepository = userRepository;
    }

    @PostMapping("/wechat/login")
    public ApiResponse<LoginResponse> wechatLogin(
            @RequestHeader(value = APP_CODE_HEADER, required = false) String appCode,
            @Valid @RequestBody WechatLoginRequest request
    ) {
        WechatLoginService.LoginResult result = wechatLoginService.login(appCode, request.code());
        return ApiResponse.success(new LoginResponse(
                result.accessToken(),
                result.tokenType(),
                result.expiresAt(),
                result.userId(),
                result.appCode()
        ));
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(Authentication authentication) {
        SessionPrincipal principal = requirePrincipal(authentication);
        UserEntity user = userRepository.findByAppIdAndId(principal.appId(), principal.userId())
                .orElseThrow(() -> new BusinessException(ApiErrorCode.UNAUTHORIZED));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ApiErrorCode.FORBIDDEN);
        }
        return ApiResponse.success(new MeResponse(
                user.getId(),
                principal.appCode(),
                user.getStatus().name()
        ));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(Authentication authentication) {
        SessionPrincipal principal = requirePrincipal(authentication);
        authSessionService.revokeCurrentSession(principal);
        return ApiResponse.success();
    }

    private static SessionPrincipal requirePrincipal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionPrincipal principal)) {
            throw new BusinessException(ApiErrorCode.UNAUTHORIZED);
        }
        return principal;
    }
}
