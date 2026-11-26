package com.aurora.appserver.auth.service;

import com.aurora.appserver.app.context.AppContext;
import com.aurora.appserver.app.service.AppRegistryService;
import com.aurora.appserver.auth.config.AuroraAuthProperties;
import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.exception.BusinessException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Username/password admin login for configured apps only (CalculatorVault).
 * Never creates WeChat users, membership grants, or payment orders.
 */
@Service
public class AdminLoginService {

    private static final Logger log = LoggerFactory.getLogger(AdminLoginService.class);
    /** Product rule: admin unlock applies only to CalculatorVault. */
    public static final String CALCULATOR_VAULT_APP_CODE = "calculator_vault";

    private final AppRegistryService appRegistryService;
    private final AuroraAuthProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final AdminSessionService adminSessionService;
    private final AdminLoginRateLimiter rateLimiter;

    public AdminLoginService(
            AppRegistryService appRegistryService,
            AuroraAuthProperties properties,
            PasswordEncoder passwordEncoder,
            AdminSessionService adminSessionService,
            AdminLoginRateLimiter rateLimiter
    ) {
        this.appRegistryService = appRegistryService;
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
        this.adminSessionService = adminSessionService;
        this.rateLimiter = rateLimiter;
    }

    public record LoginResult(
            String accessToken,
            String tokenType,
            Instant expiresAt,
            String username,
            String appCode
    ) {
    }

    public LoginResult login(String appCodeHeader, String username, String password, String clientIp) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED);
        }
        String normalizedUser = username.trim();
        AppContext app = appRegistryService.requireActiveApp(appCodeHeader);
        if (!CALCULATOR_VAULT_APP_CODE.equals(app.appCode())) {
            throw new BusinessException(ApiErrorCode.FORBIDDEN, "Admin login is not available for this application");
        }

        AuroraAuthProperties.AdminCredentials credentials = properties.getAdmin().getApps().get(app.appCode());
        if (credentials == null || !credentials.isComplete()) {
            throw new BusinessException(ApiErrorCode.ADMIN_NOT_CONFIGURED);
        }

        String rateKey = "admin-login:" + app.appCode() + ":" + normalizedUser.toLowerCase()
                + ":" + (StringUtils.hasText(clientIp) ? clientIp : "unknown");
        rateLimiter.checkOrThrow(rateKey);

        if (!credentials.isEnabled()) {
            // Invalidate any leftover sessions when the account is switched off in config.
            adminSessionService.revokeAllActiveSessions(app.appId(), credentials.getUsername());
            throw new BusinessException(ApiErrorCode.ADMIN_DISABLED);
        }

        if (!credentials.getUsername().equals(normalizedUser)
                || !passwordEncoder.matches(password, credentials.getPasswordHash())) {
            rateLimiter.recordFailure(rateKey);
            log.warn("Admin login failed appCode={}", app.appCode());
            throw new BusinessException(ApiErrorCode.ADMIN_AUTH_FAILED);
        }

        AdminSessionService.IssuedAdminSession issued =
                adminSessionService.issueSession(app.appId(), credentials.getUsername());
        log.info("Admin login ok appCode={} sessionId={}", app.appCode(), issued.session().getId());
        return new LoginResult(
                issued.rawToken(),
                "Bearer",
                issued.session().getExpiresAt(),
                credentials.getUsername(),
                app.appCode()
        );
    }
}
