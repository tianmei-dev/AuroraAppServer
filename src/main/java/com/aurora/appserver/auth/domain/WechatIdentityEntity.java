package com.aurora.appserver.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "wechat_identities")
public class WechatIdentityEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_id", nullable = false)
    private Long appId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "wechat_appid", nullable = false, length = 64)
    private String wechatAppid;

    @Column(name = "openid", nullable = false, length = 128)
    private String openid;

    @Column(name = "unionid", length = 128)
    private String unionid;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WechatIdentityEntity() {
    }

    public static WechatIdentityEntity create(
            Long appId,
            Long userId,
            String wechatAppid,
            String openid,
            String unionid
    ) {
        WechatIdentityEntity entity = new WechatIdentityEntity();
        entity.appId = appId;
        entity.userId = userId;
        entity.wechatAppid = wechatAppid;
        entity.openid = openid;
        entity.unionid = unionid;
        return entity;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getAppId() {
        return appId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getWechatAppid() {
        return wechatAppid;
    }

    public String getOpenid() {
        return openid;
    }

    public String getUnionid() {
        return unionid;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
