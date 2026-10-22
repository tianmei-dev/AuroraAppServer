package com.aurora.appserver.auth.wechat;

import com.aurora.appserver.auth.config.AuroraAuthProperties;
import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.exception.BusinessException;
import org.springframework.stereotype.Service;

@Service
public class WechatConfigService {

    private final AuroraAuthProperties properties;

    public WechatConfigService(AuroraAuthProperties properties) {
        this.properties = properties;
    }

    public AuroraAuthProperties.AppCredentials requireCredentials(String appCode) {
        AuroraAuthProperties.AppCredentials credentials =
                properties.getWechat().getApps().get(appCode);
        if (credentials == null || !credentials.isComplete()) {
            throw new BusinessException(ApiErrorCode.WECHAT_CONFIG_MISSING);
        }
        return credentials;
    }
}
