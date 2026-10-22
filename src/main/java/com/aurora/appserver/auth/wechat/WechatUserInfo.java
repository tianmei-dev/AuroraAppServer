package com.aurora.appserver.auth.wechat;

/**
 * Trusted WeChat identity after server-side code exchange.
 * Does not carry WeChat access_token.
 */
public record WechatUserInfo(String openid, String unionid) {
}
