# 多 App 隔离原则

## `app_id` 与 `app_code` 的区别

| 概念 | 含义 | 谁使用 |
|------|------|--------|
| `app_code` | 稳定的业务标识字符串，如 `calculator_vault` | 客户端请求头、运维配置、种子数据 |
| `app_id` | 数据库自增主键（内部 ID） | 服务端外键、会话绑定、查询条件 |

规则：

- **不要**在业务代码中写死数字 `app_id`。
- 通过 `AppRegistryService` 用 `app_code` 解析出内部 `app_id`。
- 用户、会话、订单等表外键一律存 `app_id`，保证引用完整性与查询性能。

## 为什么包名不能作为认证依据

Android 包名（如 `com.aurora.calculatorvault`）是公开元数据：

- 任何人都可以在请求里伪造包名或 `X-App-Code`。
- 包名不证明 APK 签名、不证明设备归属、不证明用户身份。

因此：

- `X-App-Code` **只能**用于应用路由与查找配置。
- **不能**据此开放受保护业务或写入跨 App 数据。
- S2.1 起，`app_id` 必须绑定到服务端签发的认证会话，并校验会话归属与资源归属一致。

## 后续用户、会话、订单如何隔离

建议表设计一律带 `app_id`：

```text
users(id, app_id, ...)
sessions(id, app_id, user_id, ...)
memberships(id, app_id, user_id, ...)
orders(id, app_id, user_id, ...)
```

查询模板：

```sql
SELECT * FROM orders
WHERE id = ? AND app_id = ?;   -- app_id 来自已认证会话，而非客户端原样传入
```

跨表关联时，关联双方的 `app_id` 必须一致。

## 跨 App 访问风险

典型攻击面：

1. 用 App A 的 Token 访问 App B 的用户资源。
2. 客户端篡改请求体中的 `app_id` / `app_code`。
3. 管理接口误用全局查询未加 `app_id` 条件。

缓解：

- 会话创建时写入服务端可信的 `app_id`。
- 业务层拒绝客户端擅自指定的 `app_id`。
- 资源加载后校验 `resource.appId == session.appId`。
- 代码评审检查所有业务查询是否遗漏 `app_id`。

## 如何新增第二个 App

1. 新增 Flyway 迁移（推荐），例如：

```sql
INSERT INTO apps (app_code, app_name, platform, package_name, status)
VALUES ('speaker_cleaner', '扬声器清洁助手', 'ANDROID', 'com.aurora.speakercleaner', 'ACTIVE');
```

2. 确认 `app_code` 全局唯一，且 `(platform, package_name)` 未冲突。
3. 为该 App 单独配置微信登录、支付商户等（S2+）。
4. 客户端使用自己的 `X-App-Code`；服务端解析为新的 `app_id`。

不要手工改正式环境自增 ID，也不要复用已有 `app_id`。

## 如何停用某个 App

```sql
UPDATE apps SET status = 'DISABLED' WHERE app_code = 'speaker_cleaner';
```

效果：

- `AppRegistryService.requireActiveApp(...)` 抛出 `APP_DISABLED`。
- 后续登录 / 支付等入口应拒绝该 App 的新业务。
- 历史数据保留，便于审计与恢复（改为 `ACTIVE` 即可重新启用）。

## 如何避免遗漏 `app_id` 查询条件

实践建议：

1. Repository 方法命名体现隔离：`findByIdAndAppId(...)`。
2. 领域服务方法签名强制接收 `AppContext` 或 `Long appId`。
3. 禁止仅按全局主键查询后“信任”结果；必须二次校验归属。
4. 集成测试覆盖：用 App A 会话访问 App B 资源应失败。
5. 代码评审清单中加入「本变更是否按 `app_id` 过滤」。

S1.1 已提供 `AppRegistryService` / `AppContext` 作为后续模块的统一入口。
