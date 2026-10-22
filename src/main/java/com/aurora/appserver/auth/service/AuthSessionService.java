package com.aurora.appserver.auth.service;

import com.aurora.appserver.app.context.AppContext;
import com.aurora.appserver.auth.config.AuroraAuthProperties;
import com.aurora.appserver.auth.domain.AuthSessionEntity;
import com.aurora.appserver.auth.domain.UserEntity;
import com.aurora.appserver.auth.domain.UserStatus;
import com.aurora.appserver.auth.repository.AuthSessionRepository;
import com.aurora.appserver.auth.repository.UserRepository;
import com.aurora.appserver.auth.security.SessionPrincipal;
import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.exception.BusinessException;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthSessionService {

    private final AuthSessionRepository authSessionRepository;
    private final UserRepository userRepository;
    private final SessionTokenGenerator tokenGenerator;
    private final TokenHasher tokenHasher;
    private final AuroraAuthProperties properties;

    public AuthSessionService(
            AuthSessionRepository authSessionRepository,
            UserRepository userRepository,
            SessionTokenGenerator tokenGenerator,
            TokenHasher tokenHasher,
            AuroraAuthProperties properties
    ) {
        this.authSessionRepository = authSessionRepository;
        this.userRepository = userRepository;
        this.tokenGenerator = tokenGenerator;
        this.tokenHasher = tokenHasher;
        this.properties = properties;
    }

    public record IssuedSession(String rawToken, AuthSessionEntity session) {
    }

    @Transactional
    public IssuedSession issueSession(UserEntity user) {
        String rawToken = tokenGenerator.generateRawToken();
        String tokenHash = tokenHasher.sha256Hex(rawToken);
        Instant expiresAt = Instant.now().plus(properties.getAuth().getSessionTtl());
        AuthSessionEntity session = authSessionRepository.saveAndFlush(
                AuthSessionEntity.create(user.getAppId(), user.getId(), tokenHash, expiresAt));
        return new IssuedSession(rawToken, session);
    }

    @Transactional(readOnly = true)
    public SessionPrincipal authenticate(String rawToken, AppContext requestApp) {
        String tokenHash = tokenHasher.sha256Hex(rawToken);
        AuthSessionEntity session = authSessionRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.UNAUTHORIZED));

        if (session.isRevoked()) {
            throw new BusinessException(ApiErrorCode.SESSION_REVOKED);
        }
        if (session.isExpired(Instant.now())) {
            throw new BusinessException(ApiErrorCode.SESSION_EXPIRED);
        }
        if (!session.getAppId().equals(requestApp.appId())) {
            throw new BusinessException(ApiErrorCode.APP_CONTEXT_MISMATCH);
        }

        UserEntity user = userRepository.findByAppIdAndId(session.getAppId(), session.getUserId())
                .orElseThrow(() -> new BusinessException(ApiErrorCode.UNAUTHORIZED));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ApiErrorCode.FORBIDDEN);
        }

        return new SessionPrincipal(
                session.getId(),
                session.getAppId(),
                requestApp.appCode(),
                session.getUserId(),
                session.getTokenHash()
        );
    }

    @Transactional
    public void revokeCurrentSession(SessionPrincipal principal) {
        AuthSessionEntity session = authSessionRepository
                .findByAppIdAndId(principal.appId(), principal.sessionId())
                .orElseThrow(() -> new BusinessException(ApiErrorCode.UNAUTHORIZED));
        if (!session.isRevoked()) {
            session.revoke(Instant.now());
            authSessionRepository.save(session);
        }
    }
}
