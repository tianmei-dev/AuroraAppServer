-- apps: multi-app registry (utf8mb4, UTC timestamps)
CREATE TABLE apps (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    app_code      VARCHAR(64)  NOT NULL,
    app_name      VARCHAR(128) NOT NULL,
    platform      VARCHAR(32)  NOT NULL,
    package_name  VARCHAR(255) NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_apps_app_code UNIQUE (app_code),
    CONSTRAINT uk_apps_platform_package UNIQUE (platform, package_name),
    CONSTRAINT chk_apps_platform CHECK (platform IN ('ANDROID')),
    CONSTRAINT chk_apps_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_apps_status ON apps (status);
CREATE INDEX idx_apps_package_name ON apps (package_name);
