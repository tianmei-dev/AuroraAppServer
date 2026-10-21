-- S1.2: multi-app account + device trial tables (utf8mb4, UTC)
-- Isolation relies on app_id and composite FKs (app_id, user_id) -> users(app_id, id).

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    app_id      BIGINT       NOT NULL,
    status      VARCHAR(32)  NOT NULL,
    created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_users_app_id_id UNIQUE (app_id, id),
    CONSTRAINT fk_users_app FOREIGN KEY (app_id) REFERENCES apps (id),
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_users_app_id ON users (app_id);
CREATE INDEX idx_users_app_status ON users (app_id, status);

-- ---------------------------------------------------------------------------
-- wechat_identities
-- ---------------------------------------------------------------------------
CREATE TABLE wechat_identities (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    app_id        BIGINT       NOT NULL,
    user_id       BIGINT       NOT NULL,
    wechat_appid  VARCHAR(64)  NOT NULL,
    openid        VARCHAR(128) NOT NULL,
    unionid       VARCHAR(128) NULL,
    created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_wechat_identities_app_appid_openid UNIQUE (app_id, wechat_appid, openid),
    CONSTRAINT fk_wechat_identities_app FOREIGN KEY (app_id) REFERENCES apps (id),
    CONSTRAINT fk_wechat_identities_user FOREIGN KEY (app_id, user_id) REFERENCES users (app_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_wechat_identities_user ON wechat_identities (app_id, user_id);

-- ---------------------------------------------------------------------------
-- auth_sessions
-- ---------------------------------------------------------------------------
CREATE TABLE auth_sessions (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    app_id      BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    token_hash  VARCHAR(128) NOT NULL,
    expires_at  TIMESTAMP(3) NOT NULL,
    revoked_at  TIMESTAMP(3) NULL,
    created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_auth_sessions_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_auth_sessions_app FOREIGN KEY (app_id) REFERENCES apps (id),
    CONSTRAINT fk_auth_sessions_user FOREIGN KEY (app_id, user_id) REFERENCES users (app_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_auth_sessions_user ON auth_sessions (app_id, user_id);
CREATE INDEX idx_auth_sessions_expires_at ON auth_sessions (expires_at);

-- ---------------------------------------------------------------------------
-- device_trials
-- ---------------------------------------------------------------------------
CREATE TABLE device_trials (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    app_id            BIGINT       NOT NULL,
    device_key_hash   VARCHAR(128) NOT NULL,
    first_seen_at     TIMESTAMP(3) NOT NULL,
    trial_started_at  TIMESTAMP(3) NOT NULL,
    trial_expires_at  TIMESTAMP(3) NOT NULL,
    created_at        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_device_trials_app_device UNIQUE (app_id, device_key_hash),
    CONSTRAINT fk_device_trials_app FOREIGN KEY (app_id) REFERENCES apps (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_device_trials_app_id ON device_trials (app_id);
