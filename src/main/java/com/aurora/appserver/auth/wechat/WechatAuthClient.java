package com.aurora.appserver.auth.wechat;

/**
 * Exchanges a temporary WeChat authorization code for a trusted openid.
 */
public interface WechatAuthClient {

    WechatUserInfo exchangeCode(String wechatAppId, String wechatAppSecret, String code);
}
