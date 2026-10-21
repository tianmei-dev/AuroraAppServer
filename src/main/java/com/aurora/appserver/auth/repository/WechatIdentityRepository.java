package com.aurora.appserver.auth.repository;

import com.aurora.appserver.auth.domain.WechatIdentityEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * App-scoped WeChat identity lookups. OpenID uniqueness is per (app_id, wechat_appid, openid).
 */
public interface WechatIdentityRepository extends JpaRepository<WechatIdentityEntity, Long> {

    Optional<WechatIdentityEntity> findByAppIdAndWechatAppidAndOpenid(Long appId, String wechatAppid, String openid);

    Optional<WechatIdentityEntity> findByAppIdAndId(Long appId, Long id);

    boolean existsByAppIdAndWechatAppidAndOpenid(Long appId, String wechatAppid, String openid);

    long countByAppIdAndUserId(Long appId, Long userId);
}
