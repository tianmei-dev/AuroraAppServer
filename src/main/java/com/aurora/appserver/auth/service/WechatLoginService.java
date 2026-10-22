package com.aurora.appserver.auth.service;

import com.aurora.appserver.app.context.AppContext;
import com.aurora.appserver.app.service.AppRegistryService;
import com.aurora.appserver.auth.config.AuroraAuthProperties;
import com.aurora.appserver.auth.domain.UserEntity;
import com.aurora.appserver.auth.domain.UserStatus;
import com.aurora.appserver.auth.domain.WechatIdentityEntity;
import com.aurora.appserver.auth.repository.UserRepository;
import com.aurora.appserver.auth.repository.WechatIdentityRepository;
import com.aurora.appserver.auth.wechat.WechatAuthClient;
import com.aurora.appserver.auth.wechat.WechatConfigService;
import com.aurora.appserver.auth.wechat.WechatUserInfo;
import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class WechatLoginService {

    private static final Logger log = LoggerFactory.getLogger(WechatLoginService.class);

    private final AppRegistryService appRegistryService;
    private final WechatConfigService wechatConfigService;
    private final WechatAuthClient wechatAuthClient;
    private final WechatIdentityRepository wechatIdentityRepository;
    private final UserRepository userRepository;
    private final AuthSessionService authSessionService;
    private final WechatLoginService self;

    public WechatLoginService(
            AppRegistryService appRegistryService,
            WechatConfigService wechatConfigService,
            WechatAuthClient wechatAuthClient,
            WechatIdentityRepository wechatIdentityRepository,
            UserRepository userRepository,
            AuthSessionService authSessionService,
            @org.springframework.context.annotation.Lazy WechatLoginService self
    ) {
        this.appRegistryService = appRegistryService;
        this.wechatConfigService = wechatConfigService;
        this.wechatAuthClient = wechatAuthClient;
        this.wechatIdentityRepository = wechatIdentityRepository;
        this.userRepository = userRepository;
        this.authSessionService = authSessionService;
        this.self = self;
    }

    public record LoginResult(
            String accessToken,
            String tokenType,
            java.time.Instant expiresAt,
            Long userId,
            String appCode
    ) {
    }

    /**
     * WeChat code exchange is outside DB transactions. User/identity creation uses a short
     * REQUIRES_NEW transaction; unique conflicts are retried via a fresh lookup (never reuse a
     * failed transaction).
     */
    public LoginResult login(String appCodeHeader, String code) {
        if (!StringUtils.hasText(code)) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED);
        }

        AppContext app = appRegistryService.requireActiveApp(appCodeHeader);
        AuroraAuthProperties.AppCredentials credentials =
                wechatConfigService.requireCredentials(app.appCode());

        WechatUserInfo wechatUser = wechatAuthClient.exchangeCode(
                credentials.getAppId(),
                credentials.getAppSecret(),
                code.trim()
        );

        UserEntity user = resolveUser(app.appId(), credentials.getAppId(), wechatUser);
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ApiErrorCode.FORBIDDEN);
        }

        AuthSessionService.IssuedSession issued = authSessionService.issueSession(user);
        return new LoginResult(
                issued.rawToken(),
                "Bearer",
                issued.session().getExpiresAt(),
                user.getId(),
                app.appCode()
        );
    }

    UserEntity resolveUser(Long appId, String wechatAppid, WechatUserInfo wechatUser) {
        return wechatIdentityRepository
                .findByAppIdAndWechatAppidAndOpenid(appId, wechatAppid, wechatUser.openid())
                .map(identity -> loadActiveScopedUser(appId, identity.getUserId()))
                .orElseGet(() -> createUserHandlingRace(appId, wechatAppid, wechatUser));
    }

    private UserEntity createUserHandlingRace(Long appId, String wechatAppid, WechatUserInfo wechatUser) {
        try {
            return self.createUserAndIdentityInNewTx(appId, wechatAppid, wechatUser);
        } catch (DataAccessException ex) {
            // REQUIRES_NEW tx is already rolled back; never continue that failed transaction.
            log.info("Concurrent WeChat identity create detected; recovering existing user");
            return wechatIdentityRepository
                    .findByAppIdAndWechatAppidAndOpenid(appId, wechatAppid, wechatUser.openid())
                    .map(identity -> loadActiveScopedUser(appId, identity.getUserId()))
                    .orElseThrow(() -> new BusinessException(ApiErrorCode.INTERNAL_ERROR));
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserEntity createUserAndIdentityInNewTx(Long appId, String wechatAppid, WechatUserInfo wechatUser) {
        // Re-check inside the new transaction to reduce unnecessary inserts under load.
        return wechatIdentityRepository
                .findByAppIdAndWechatAppidAndOpenid(appId, wechatAppid, wechatUser.openid())
                .map(identity -> loadActiveScopedUser(appId, identity.getUserId()))
                .orElseGet(() -> {
                    UserEntity user = userRepository.saveAndFlush(UserEntity.create(appId, UserStatus.ACTIVE));
                    wechatIdentityRepository.saveAndFlush(WechatIdentityEntity.create(
                            appId,
                            user.getId(),
                            wechatAppid,
                            wechatUser.openid(),
                            wechatUser.unionid()
                    ));
                    return user;
                });
    }

    private UserEntity loadActiveScopedUser(Long appId, Long userId) {
        return userRepository.findByAppIdAndId(appId, userId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.INTERNAL_ERROR));
    }
}
