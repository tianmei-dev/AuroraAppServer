package com.aurora.appserver.auth.service;

import com.aurora.appserver.app.context.AppContext;
import com.aurora.appserver.auth.config.AuroraAuthProperties;
import com.aurora.appserver.auth.domain.AdminSessionEntity;
import com.aurora.appserver.auth.repository.AdminSessionRepository;
import com.aurora.appserver.auth.security.AdminPrincipal;
import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminSessionService {

    private final AdminSessionRepository adminSessionRepository;
    private final SessionTokenGenerator tokenGenerator;
    private final TokenHasher tokenHasher;
    private final AuroraAuthProperties properties;
    private final Clock clock;

    public AdminSessionService(
            AdminSessionRepository adminSessionRepository,
            SessionTokenGenerator tokenGenerator,
            TokenHasher tokenHasher,
            AuroraAuthProperties properties,
            Clock clock
    ) {
        this.adminSessionRepository = adminSessionRepository;
        this.tokenGenerator = tokenGenerator;
        this.tokenHasher = tokenHasher;
        this.properties = properties;
        this.clock = clock;
    }

    public record IssuedAdminSession(String rawToken, AdminSessionEntity session) {
    }

    @Transactional
    public IssuedAdminSession issueSession(Long appId, String username) {
        String rawToken = tokenGenerator.generateRawToken();
        String tokenHash = tokenHasher.sha256Hex(rawToken);
        Instant expiresAt = clock.instant().plus(properties.getAdmin().getSessionTtl());
        AdminSessionEntity session = adminSessionRepository.saveAndFlush(
                AdminSessionEntity.create(appId, username, tokenHash, expiresAt));
        return new IssuedAdminSession(rawToken, session);
    }

    @Transactional(readOnly = true)
    public AdminPrincipal authenticate(String rawToken, AppContext requestApp) {
        String tokenHash = tokenHasher.sha256Hex(rawToken);
        AdminSessionEntity session = adminSessionRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.UNAUTHORIZED));

        if (session.isRevoked()) {
            throw new BusinessException(ApiErrorCode.SESSION_REVOKED);
        }
        if (session.isExpired(clock.instant())) {
            throw new BusinessException(ApiErrorCode.SESSION_EXPIRED);
        }
        if (!session.getAppId().equals(requestApp.appId())) {
            throw new BusinessException(ApiErrorCode.APP_CONTEXT_MISMATCH);
        }

        AuroraAuthProperties.AdminCredentials credentials =
                properties.getAdmin().getApps().get(requestApp.appCode());
        if (credentials == null || !credentials.isComplete()) {
            throw new BusinessException(ApiErrorCode.ADMIN_NOT_CONFIGURED);
        }
        if (!credentials.isEnabled()) {
            throw new BusinessException(ApiErrorCode.ADMIN_DISABLED);
        }
        if (!credentials.getUsername().equals(session.getAdminUsername())) {
            throw new BusinessException(ApiErrorCode.UNAUTHORIZED);
        }

        return new AdminPrincipal(
                session.getId(),
                session.getAppId(),
                requestApp.appCode(),
                session.getAdminUsername(),
                session.getTokenHash()
        );
    }

    @Transactional
    public void revokeCurrentSession(AdminPrincipal principal) {
        AdminSessionEntity session = adminSessionRepository
                .findByAppIdAndId(principal.appId(), principal.sessionId())
                .orElseThrow(() -> new BusinessException(ApiErrorCode.UNAUTHORIZED));
        if (!session.isRevoked()) {
            session.revoke(clock.instant());
            adminSessionRepository.save(session);
        }
    }

    @Transactional
    public int revokeAllActiveSessions(Long appId, String username) {
        return adminSessionRepository.revokeAllActiveForAdmin(appId, username, clock.instant());
    }
}
