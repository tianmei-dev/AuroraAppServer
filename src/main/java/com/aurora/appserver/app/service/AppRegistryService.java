package com.aurora.appserver.app.service;

import com.aurora.appserver.app.context.AppContext;
import com.aurora.appserver.app.domain.AppEntity;
import com.aurora.appserver.app.domain.AppStatus;
import com.aurora.appserver.app.repository.AppRepository;
import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AppRegistryService {

    private final AppRepository appRepository;

    public AppRegistryService(AppRepository appRepository) {
        this.appRepository = appRepository;
    }

    @Transactional(readOnly = true)
    public AppEntity findByAppCode(String appCode) {
        requireAppCode(appCode);
        return appRepository.findByAppCode(appCode.trim())
                .orElseThrow(() -> new BusinessException(ApiErrorCode.APP_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public boolean exists(String appCode) {
        if (!StringUtils.hasText(appCode)) {
            return false;
        }
        return appRepository.existsByAppCode(appCode.trim());
    }

    @Transactional(readOnly = true)
    public boolean isActive(String appCode) {
        if (!StringUtils.hasText(appCode)) {
            return false;
        }
        return appRepository.findByAppCode(appCode.trim())
                .map(AppEntity::isActive)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public Long getAppId(String appCode) {
        return findByAppCode(appCode).getId();
    }

    /**
     * Resolve an active application context from client-supplied app_code.
     * X-App-Code is only for routing/config lookup — not authentication.
     */
    @Transactional(readOnly = true)
    public AppContext requireActiveApp(String appCode) {
        requireAppCode(appCode);
        AppEntity app = findByAppCode(appCode);
        if (app.getStatus() != AppStatus.ACTIVE) {
            throw new BusinessException(ApiErrorCode.APP_DISABLED);
        }
        return toContext(app);
    }

    @Transactional(readOnly = true)
    public AppContext resolveOptional(String appCode) {
        if (!StringUtils.hasText(appCode)) {
            throw new BusinessException(ApiErrorCode.APP_CONTEXT_REQUIRED);
        }
        return requireActiveApp(appCode);
    }

    private static void requireAppCode(String appCode) {
        if (!StringUtils.hasText(appCode)) {
            throw new BusinessException(ApiErrorCode.APP_CONTEXT_REQUIRED);
        }
    }

    private static AppContext toContext(AppEntity app) {
        return new AppContext(
                app.getId(),
                app.getAppCode(),
                app.getAppName(),
                app.getPlatform(),
                app.getPackageName(),
                app.getStatus()
        );
    }
}
