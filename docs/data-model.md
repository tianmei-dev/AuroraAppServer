# 数据模型（S1.1）

## 数据库

- 名称：`aurora_app`
- 字符集：`utf8mb4` / `utf8mb4_unicode_ci`
- 时区：UTC（连接与 Hibernate `jdbc.time_zone=UTC`）
- Schema 管理：仅 Flyway（`jpa.hibernate.ddl-auto=validate`）

## 表：`apps`

| 列 | 类型 | 约束 | 说明 |
|----|------|------|------|
| `id` | BIGINT | PK, AI | 内部 `app_id` |
| `app_code` | VARCHAR(64) | NOT NULL, UNIQUE | 业务标识 |
| `app_name` | VARCHAR(128) | NOT NULL | 展示名 |
| `platform` | VARCHAR(32) | NOT NULL, CHECK ∈ {ANDROID} | 平台 |
| `package_name` | VARCHAR(255) | NOT NULL | Android 包名 |
| `status` | VARCHAR(32) | NOT NULL, CHECK ∈ {ACTIVE, DISABLED} | 状态 |
| `created_at` | TIMESTAMP(3) | NOT NULL, UTC | 创建时间 |
| `updated_at` | TIMESTAMP(3) | NOT NULL, UTC, ON UPDATE | 更新时间 |

### 唯一约束

1. `uk_apps_app_code`：`app_code`
2. `uk_apps_platform_package`：`(platform, package_name)`

### 索引

- `idx_apps_status`
- `idx_apps_package_name`

## 种子数据

| app_code | app_name | platform | package_name | status |
|----------|----------|----------|--------------|--------|
| `calculator_vault` | 隐藏计算器 | ANDROID | `com.aurora.calculatorvault` | ACTIVE |

迁移文件：

- `V1__create_apps_table.sql`
- `V2__seed_calculator_vault.sql`

## 后续表（未实现）

所有业务表默认应包含明确的 `app_id` 外键，例如：

- `users.app_id`
- `auth_sessions.app_id`
- `device_trials.app_id`
- `memberships.app_id`
- `orders.app_id`
