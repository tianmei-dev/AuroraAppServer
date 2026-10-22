package com.aurora.appserver.auth.support;

import com.aurora.appserver.auth.wechat.WechatAuthClient;
import com.aurora.appserver.auth.wechat.WechatUserInfo;
import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.exception.BusinessException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Controllable WeChat client for integration tests. Never calls real WeChat APIs.
 */
public class TestWechatAuthClient implements WechatAuthClient {

    private final Map<String, Function<String, WechatUserInfo>> handlers = new ConcurrentHashMap<>();
    private Function<String, WechatUserInfo> defaultHandler =
            code -> new WechatUserInfo("openid-" + code, null);

    public void reset() {
        handlers.clear();
        defaultHandler = code -> new WechatUserInfo("openid-" + code, null);
    }

    public void onCode(String code, Function<String, WechatUserInfo> handler) {
        handlers.put(code, handler);
    }

    public void setDefaultHandler(Function<String, WechatUserInfo> handler) {
        this.defaultHandler = handler;
    }

    @Override
    public WechatUserInfo exchangeCode(String wechatAppId, String wechatAppSecret, String code) {
        Function<String, WechatUserInfo> handler = handlers.getOrDefault(code, defaultHandler);
        return handler.apply(code);
    }

    public static WechatUserInfo ok(String openid) {
        return new WechatUserInfo(openid, null);
    }

    public static WechatUserInfo ok(String openid, String unionid) {
        return new WechatUserInfo(openid, unionid);
    }

    public static Function<String, WechatUserInfo> invalidCode() {
        return code -> {
            throw new BusinessException(ApiErrorCode.WECHAT_CODE_INVALID);
        };
    }

    public static Function<String, WechatUserInfo> authFailed() {
        return code -> {
            throw new BusinessException(ApiErrorCode.WECHAT_AUTH_FAILED);
        };
    }

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        TestWechatAuthClient testWechatAuthClient() {
            return new TestWechatAuthClient();
        }
    }
}
