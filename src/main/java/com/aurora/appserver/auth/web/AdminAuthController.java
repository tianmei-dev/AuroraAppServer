package com.aurora.appserver.auth.web;

import com.aurora.appserver.auth.security.AdminPrincipal;
import com.aurora.appserver.auth.service.AdminLoginService;
import com.aurora.appserver.auth.service.AdminSessionService;
import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.api.ApiResponse;
import com.aurora.appserver.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/admin")
public class AdminAuthController {

    public static final String APP_CODE_HEADER = AuthController.APP_CODE_HEADER;

    private final AdminLoginService adminLoginService;
    private final AdminSessionService adminSessionService;

    public AdminAuthController(AdminLoginService adminLoginService, AdminSessionService adminSessionService) {
        this.adminLoginService = adminLoginService;
        this.adminSessionService = adminSessionService;
    }

    @PostMapping("/login")
    public ApiResponse<AdminLoginResponse> login(
            @RequestHeader(value = APP_CODE_HEADER, required = false) String appCode,
            @Valid @RequestBody AdminLoginRequest request,
            HttpServletRequest httpRequest
    ) {
        AdminLoginService.LoginResult result = adminLoginService.login(
                appCode,
                request.username(),
                request.password(),
                clientIp(httpRequest)
        );
        return ApiResponse.success(new AdminLoginResponse(
                result.accessToken(),
                result.tokenType(),
                result.expiresAt(),
                result.username(),
                result.appCode()
        ));
    }

    @GetMapping("/me")
    public ApiResponse<AdminMeResponse> me(Authentication authentication) {
        AdminPrincipal principal = requireAdmin(authentication);
        return ApiResponse.success(new AdminMeResponse(
                principal.username(),
                principal.appCode(),
                "ADMIN"
        ));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(Authentication authentication) {
        AdminPrincipal principal = requireAdmin(authentication);
        adminSessionService.revokeCurrentSession(principal);
        return ApiResponse.success();
    }

    private static AdminPrincipal requireAdmin(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AdminPrincipal principal)) {
            throw new BusinessException(ApiErrorCode.UNAUTHORIZED);
        }
        return principal;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
