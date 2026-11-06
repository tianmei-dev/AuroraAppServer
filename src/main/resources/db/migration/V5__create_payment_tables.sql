-- I3.5.3-A: payment order foundation (Yuesen unified checkout). No refunds table in v1.
-- Isolation: orders carry app_id with composite FK (app_id, user_id) -> users(app_id, id).

-- ---------------------------------------------------------------------------
-- payment_orders
-- ---------------------------------------------------------------------------
CREATE TABLE payment_orders (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    app_id                BIGINT       NOT NULL,
    user_id               BIGINT       NOT NULL,
    order_no              VARCHAR(32)  NOT NULL,
    client_request_id     VARCHAR(64)  NOT NULL,
    plan_id               BIGINT       NOT NULL,
    plan_code             VARCHAR(32)  NOT NULL,
    plan_display_name     VARCHAR(128) NOT NULL,
    amount_fen            INT          NOT NULL,
    currency              VARCHAR(3)   NOT NULL,
    duration_days         INT          NULL,
    is_lifetime           TINYINT(1)   NOT NULL DEFAULT 0,
    provider              VARCHAR(32)  NOT NULL,
    environment           VARCHAR(16)  NOT NULL,
    merchant_pid          VARCHAR(64)  NULL,
    status                VARCHAR(32)  NOT NULL,
    provider_trade_no     VARCHAR(64)  NULL,
    upstream_trade_no     VARCHAR(64)  NULL,
    paid_channel_raw      VARCHAR(32)  NULL,
    paid_amount_fen       INT          NULL,
    provider_paid_at_raw  VARCHAR(32)  NULL,
    expires_at            TIMESTAMP(3) NOT NULL,
    paid_at               TIMESTAMP(3) NULL,
    fulfilled_at          TIMESTAMP(3) NULL,
    expired_at            TIMESTAMP(3) NULL,
    late_paid             TINYINT(1)   NOT NULL DEFAULT 0,
    anomaly_code          VARCHAR(64)  NULL,
    membership_grant_id   BIGINT       NULL,
    fulfill_attempts      INT          NOT NULL DEFAULT 0,
    next_fulfill_at       TIMESTAMP(3) NULL,
    last_fulfill_error    VARCHAR(255) NULL,
    query_attempts        INT          NOT NULL DEFAULT 0,
    last_query_at         TIMESTAMP(3) NULL,
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at            TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_orders_order_no UNIQUE (order_no),
    CONSTRAINT uk_payment_orders_client_request UNIQUE (app_id, user_id, client_request_id),
    CONSTRAINT uk_payment_orders_provider_trade UNIQUE (provider, provider_trade_no),
    CONSTRAINT fk_payment_orders_app FOREIGN KEY (app_id) REFERENCES apps (id),
    CONSTRAINT fk_payment_orders_user FOREIGN KEY (app_id, user_id) REFERENCES users (app_id, id),
    CONSTRAINT fk_payment_orders_plan FOREIGN KEY (plan_id) REFERENCES membership_plans (id),
    CONSTRAINT chk_payment_orders_status CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'FULFILLED', 'EXPIRED')),
    CONSTRAINT chk_payment_orders_amount CHECK (amount_fen > 0),
    CONSTRAINT chk_payment_orders_currency CHECK (currency IN ('CNY')),
    CONSTRAINT chk_payment_orders_provider CHECK (provider IN ('YUSEN', 'FAKE')),
    CONSTRAINT chk_payment_orders_environment CHECK (environment IN ('PRODUCTION', 'STAGING', 'TEST')),
    CONSTRAINT chk_payment_orders_no_fake_in_prod CHECK (environment <> 'PRODUCTION' OR provider <> 'FAKE'),
    CONSTRAINT chk_payment_orders_lifetime CHECK (
        (is_lifetime = 1 AND duration_days IS NULL)
            OR (is_lifetime = 0 AND duration_days IS NOT NULL AND duration_days > 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_payment_orders_status_expires ON payment_orders (status, expires_at);
CREATE INDEX idx_payment_orders_status_fulfill ON payment_orders (status, next_fulfill_at);
CREATE INDEX idx_payment_orders_user_plan_status ON payment_orders (app_id, user_id, plan_code, status);
CREATE INDEX idx_payment_orders_user_created ON payment_orders (app_id, user_id, created_at);

-- ---------------------------------------------------------------------------
-- payment_order_requests: every clientRequestId (new order or reuse) maps to exactly one order.
-- Keeps clientRequestId strictly idempotent even when a request reuses an existing pending order.
-- ---------------------------------------------------------------------------
CREATE TABLE payment_order_requests (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    app_id             BIGINT       NOT NULL,
    user_id            BIGINT       NOT NULL,
    client_request_id  VARCHAR(64)  NOT NULL,
    order_id           BIGINT       NOT NULL,
    plan_code          VARCHAR(32)  NOT NULL,
    reused             TINYINT(1)   NOT NULL DEFAULT 0,
    created_at         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_order_requests_client UNIQUE (app_id, user_id, client_request_id),
    CONSTRAINT fk_payment_order_requests_user FOREIGN KEY (app_id, user_id) REFERENCES users (app_id, id),
    CONSTRAINT fk_payment_order_requests_order FOREIGN KEY (order_id) REFERENCES payment_orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_payment_order_requests_order ON payment_order_requests (order_id);

-- ---------------------------------------------------------------------------
-- payment_notifications: inbound notify / return evidence (no secrets stored)
-- ---------------------------------------------------------------------------
CREATE TABLE payment_notifications (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    provider           VARCHAR(32)   NOT NULL,
    source             VARCHAR(16)   NOT NULL,
    order_no           VARCHAR(64)   NULL,
    provider_trade_no  VARCHAR(64)   NULL,
    trade_status       VARCHAR(32)   NULL,
    amount_raw         VARCHAR(32)   NULL,
    channel_raw        VARCHAR(32)   NULL,
    pid_match          TINYINT(1)    NOT NULL DEFAULT 0,
    sign_valid         TINYINT(1)    NOT NULL DEFAULT 0,
    params_sanitized   VARCHAR(2000) NULL,
    dedupe_key         VARCHAR(64)   NOT NULL,
    process_result     VARCHAR(32)   NOT NULL,
    remote_ip          VARCHAR(64)   NULL,
    received_at        TIMESTAMP(3)  NOT NULL,
    processed_at       TIMESTAMP(3)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_notifications_dedupe UNIQUE (provider, dedupe_key),
    CONSTRAINT chk_payment_notifications_source CHECK (source IN ('NOTIFY', 'RETURN')),
    CONSTRAINT chk_payment_notifications_result CHECK (process_result IN (
        'APPLIED', 'DUPLICATE', 'IGNORED_STATUS', 'REJECTED_SIGN', 'REJECTED_PID',
        'ORDER_NOT_FOUND', 'AMOUNT_MISMATCH', 'PENDING_CONFIRM'
    ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_payment_notifications_order ON payment_notifications (order_no, received_at);

-- ---------------------------------------------------------------------------
-- payment_provider_calls: outbound call journal. Never stores URLs or merchant keys.
-- ---------------------------------------------------------------------------
CREATE TABLE payment_provider_calls (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    provider       VARCHAR(32)  NOT NULL,
    operation      VARCHAR(16)  NOT NULL,
    order_no       VARCHAR(64)  NOT NULL,
    http_status    INT          NULL,
    provider_code  VARCHAR(16)  NULL,
    provider_msg   VARCHAR(255) NULL,
    result         VARCHAR(16)  NOT NULL,
    duration_ms    INT          NULL,
    created_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT chk_payment_provider_calls_operation CHECK (operation IN ('QUERY')),
    CONSTRAINT chk_payment_provider_calls_result CHECK (result IN ('PAID', 'NOT_CONFIRMED', 'ERROR', 'TIMEOUT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_payment_provider_calls_order ON payment_provider_calls (order_no, created_at);

-- ---------------------------------------------------------------------------
-- payment_order_events: status transition audit
-- ---------------------------------------------------------------------------
CREATE TABLE payment_order_events (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    order_id        BIGINT       NOT NULL,
    from_status     VARCHAR(32)  NULL,
    to_status       VARCHAR(32)  NOT NULL,
    trigger_source  VARCHAR(32)  NOT NULL,
    detail          VARCHAR(255) NULL,
    created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT fk_payment_order_events_order FOREIGN KEY (order_id) REFERENCES payment_orders (id),
    CONSTRAINT chk_payment_order_events_trigger CHECK (trigger_source IN (
        'CREATE', 'REUSE', 'NOTIFY', 'QUERY', 'EXPIRE_JOB', 'FULFILL', 'RECOVERY'
    ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_payment_order_events_order ON payment_order_events (order_id, created_at);
