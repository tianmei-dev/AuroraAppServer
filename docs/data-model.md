# 数据模型（S1.1 / S1.2）

## 数据库

- 名称：`aurora_app`（开发）/ `aurora_app_test`（集成测试）
- 字符集：`utf8mb4` / `utf8mb4_unicode_ci`
- 时区：UTC（连接与 Hibernate `jdbc.time_zone=UTC`）
- Schema 管理：仅 Flyway（`jpa.hibernate.ddl-auto=validate`）

## 表关系（概览）

```text
apps
  ├── users (app_id)
  │     ├── wechat_identities (app_id, user_id)  -- 复合 FK → users(app_id, id)
  │     └── auth_sessions     (app_id, user_id)  -- 复合 FK → users(app_id, id)
  └── device_trials (app_id)
```

## 表：`apps`（S1.1）

| 列 | 类型 | 约束 | 说明 |
|----|------|------|------|
| `id` | BIGINT | PK, AI | 内部 `app_id` |
| `app_code` | VARCHAR(64) | NOT NULL, UNIQUE | 业务标识 |
| `app_name` | VARCHAR(128) | NOT NULL | 展示名 |
| `platform` | VARCHAR(32) | NOT NULL, CHECK ∈ {ANDROID} | 平台 |
| `package_name` | VARCHAR(255) | NOT NULL | Android 包名 |
| `status` | VARCHAR(32) | NOT NULL, CHECK ∈ {ACTIVE, DISABLED} | 状态 |
| `created_at` / `updated_at` | TIMESTAMP(3) | NOT NULL, UTC | 时间 |

唯一约束：`app_code`；`(platform, package_name)`。

种子：`calculator_vault` / 隐藏计算器 / `com.aurora.calculatorvault` / ACTIVE。

## 表：`users`（S1.2）

| 列 | 类型 | 约束 | 说明 |
|----|------|------|------|
| `id` | BIGINT | PK, AI | 内部用户 ID（按 App 独立） |
| `app_id` | BIGINT | NOT NULL, FK → apps.id | 应用归属 |
| `status` | VARCHAR(32) | CHECK ∈ {ACTIVE, DISABLED} | 状态 |
| `created_at` / `updated_at` | TIMESTAMP(3) | NOT NULL | 时间 |

关键约束：

- `uk_users_app_id_id UNIQUE (app_id, id)` — 供复合外键引用
- `fk_users_app`

## 表：`wechat_identities`（S1.2）

| 列 | 类型 | 约束 | 说明 |
|----|------|------|------|
| `id` | BIGINT | PK, AI | |
| `app_id` | BIGINT | NOT NULL, FK → apps.id | |
| `user_id` | BIGINT | NOT NULL | 与 app_id 组成复合 FK |
| `wechat_appid` | VARCHAR(64) | NOT NULL | 该 App 的微信开放平台 AppId |
| `openid` | VARCHAR(128) | NOT NULL | |
| `unionid` | VARCHAR(128) | **可空** | 不作为必填 |
| `created_at` / `updated_at` | TIMESTAMP(3) | | |

约束：

- `UNIQUE (app_id, wechat_appid, openid)`
- `FOREIGN KEY (app_id, user_id) REFERENCES users(app_id, id)`

**不存储**微信授权 code / access_token。

同一 OpenID 可在不同 App 绑定不同 `user_id`；同一 App 内不可重复。

## 表：`auth_sessions`（S1.2）

| 列 | 类型 | 约束 | 说明 |
|----|------|------|------|
| `id` | BIGINT | PK, AI | |
| `app_id` | BIGINT | NOT NULL, FK → apps.id | |
| `user_id` | BIGINT | NOT NULL | 复合 FK → users |
| `token_hash` | VARCHAR(128) | NOT NULL, UNIQUE | 仅存哈希 |
| `expires_at` | TIMESTAMP(3) | NOT NULL | |
| `revoked_at` | TIMESTAMP(3) | 可空 | |
| `created_at` | TIMESTAMP(3) | NOT NULL | |

约束：

- `FOREIGN KEY (app_id, user_id) REFERENCES users(app_id, id)`
- `UNIQUE (token_hash)`

**不存储**明文 Token。会话与用户必须同属一个 App（由复合 FK 保证）。

## 表：`device_trials`（S1.2 结构 / S3.1 业务）

| 列 | 类型 | 约束 | 说明 |
|----|------|------|------|
| `id` | BIGINT | PK, AI | 不对外返回 |
| `app_id` | BIGINT | NOT NULL, FK → apps.id | |
| `device_key_hash` | VARCHAR(128) | NOT NULL | HMAC-SHA256 hex，非原始 key |
| `first_seen_at` | TIMESTAMP(3) | NOT NULL | 首次登记（UTC） |
| `trial_started_at` | TIMESTAMP(3) | NOT NULL | = 首次登记时间 |
| `trial_expires_at` | TIMESTAMP(3) | NOT NULL | started + 72h |
| `created_at` / `updated_at` | TIMESTAMP(3) | | |

约束：`UNIQUE (app_id, device_key_hash)`。

S3.1 **未新增迁移**：复用 V3 表；无 V4。

## 迁移文件

- `V1__create_apps_table.sql`
- `V2__seed_calculator_vault.sql`
- `V3__create_account_and_trial_tables.sql`

## 后续表（未实现）

- `memberships.app_id`
- `orders.app_id`
