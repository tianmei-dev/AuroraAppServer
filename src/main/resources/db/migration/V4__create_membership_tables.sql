-- S4.1: multi-app membership plans + grant ledger + entitlement lock row
-- Isolation: every row carries app_id; grants/entitlements use composite FK (app_id, user_id) -> users(app_id, id).

-- ---------------------------------------------------------------------------
-- membership_plans
-- ---------------------------------------------------------------------------
CREATE TABLE membership_plans (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    app_id         BIGINT       NOT NULL,
    plan_code      VARCHAR(32)  NOT NULL,
    display_name   VARCHAR(128) NOT NULL,
    price_fen      INT          NOT NULL,
    duration_days  INT          NULL,
    is_lifetime    TINYINT(1)   NOT NULL DEFAULT 0,
    status         VARCHAR(32)  NOT NULL,
    created_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_membership_plans_app_code UNIQUE (app_id, plan_code),
    CONSTRAINT fk_membership_plans_app FOREIGN KEY (app_id) REFERENCES apps (id),
    CONSTRAINT chk_membership_plans_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT chk_membership_plans_price CHECK (price_fen >= 0),
    CONSTRAINT chk_membership_plans_lifetime CHECK (
        (is_lifetime = 1 AND duration_days IS NULL)
            OR (is_lifetime = 0 AND duration_days IS NOT NULL AND duration_days > 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_membership_plans_app_status ON membership_plans (app_id, status);

-- Seed CalculatorVault plans (prices in fen; durations are fixed day counts, not calendar months/years).
INSERT INTO membership_plans (app_id, plan_code, display_name, price_fen, duration_days, is_lifetime, status)
SELECT id, 'MONTHLY', '月卡', 590, 30, 0, 'ACTIVE' FROM apps WHERE app_code = 'calculator_vault';

INSERT INTO membership_plans (app_id, plan_code, display_name, price_fen, duration_days, is_lifetime, status)
SELECT id, 'YEARLY', '年卡', 1990, 365, 0, 'ACTIVE' FROM apps WHERE app_code = 'calculator_vault';

INSERT INTO membership_plans (app_id, plan_code, display_name, price_fen, duration_days, is_lifetime, status)
SELECT id, 'LIFETIME', '永久会员', 2990, NULL, 1, 'ACTIVE' FROM apps WHERE app_code = 'calculator_vault';

-- ---------------------------------------------------------------------------
-- membership_grants (immutable ledger; revoke by setting revoked_at, never DELETE)
-- ---------------------------------------------------------------------------
CREATE TABLE membership_grants (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    app_id           BIGINT       NOT NULL,
    user_id          BIGINT       NOT NULL,
    plan_id          BIGINT       NOT NULL,
    plan_code        VARCHAR(32)  NOT NULL,
    source_type      VARCHAR(32)  NOT NULL,
    source_ref       VARCHAR(128) NOT NULL,
    order_id         VARCHAR(128) NULL,
    duration_days    INT          NULL,
    is_lifetime      TINYINT(1)   NOT NULL DEFAULT 0,
    granted_at       TIMESTAMP(3) NOT NULL,
    revoked_at       TIMESTAMP(3) NULL,
    revoke_reason    VARCHAR(255) NULL,
    created_at       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_membership_grants_source UNIQUE (app_id, source_type, source_ref),
    CONSTRAINT fk_membership_grants_app FOREIGN KEY (app_id) REFERENCES apps (id),
    CONSTRAINT fk_membership_grants_user FOREIGN KEY (app_id, user_id) REFERENCES users (app_id, id),
    CONSTRAINT fk_membership_grants_plan FOREIGN KEY (plan_id) REFERENCES membership_plans (id),
    CONSTRAINT chk_membership_grants_source CHECK (source_type IN ('PAYMENT', 'ADMIN', 'PROMOTION', 'TEST')),
    CONSTRAINT chk_membership_grants_lifetime CHECK (
        (is_lifetime = 1 AND duration_days IS NULL)
            OR (is_lifetime = 0 AND duration_days IS NOT NULL AND duration_days > 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_membership_grants_user ON membership_grants (app_id, user_id, granted_at, id);
CREATE INDEX idx_membership_grants_user_active ON membership_grants (app_id, user_id, revoked_at);
CREATE INDEX idx_membership_grants_order ON membership_grants (app_id, order_id);

-- ---------------------------------------------------------------------------
-- membership_entitlements (per-user lock + materialized summary; grants remain source of truth)
-- ---------------------------------------------------------------------------
CREATE TABLE membership_entitlements (
    app_id         BIGINT       NOT NULL,
    user_id        BIGINT       NOT NULL,
    is_lifetime    TINYINT(1)   NOT NULL DEFAULT 0,
    started_at     TIMESTAMP(3) NULL,
    expires_at     TIMESTAMP(3) NULL,
    updated_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (app_id, user_id),
    CONSTRAINT fk_membership_entitlements_user FOREIGN KEY (app_id, user_id) REFERENCES users (app_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
