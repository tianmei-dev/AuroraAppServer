-- Admin login sessions (independent of WeChat users / auth_sessions).
CREATE TABLE admin_sessions (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    app_id          BIGINT       NOT NULL,
    admin_username  VARCHAR(64)  NOT NULL,
    token_hash      VARCHAR(128) NOT NULL,
    expires_at      TIMESTAMP(3) NOT NULL,
    revoked_at      TIMESTAMP(3) NULL,
    created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_admin_sessions_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_admin_sessions_app FOREIGN KEY (app_id) REFERENCES apps (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_admin_sessions_app_user ON admin_sessions (app_id, admin_username);
CREATE INDEX idx_admin_sessions_expires_at ON admin_sessions (expires_at);
