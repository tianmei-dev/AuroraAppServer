# 多 App 隔离原则

## `app_id` 与 `app_code` 的区别

| 概念 | 含义 | 谁使用 |
|------|------|--------|
| `app_code` | 稳定的业务标识字符串，如 `calculator_vault` | 客户端请求头、运维配置、种子数据 |
| `app_id` | 数据库自增主键（内部 ID） | 服务端外键、会话绑定、查询条件 |

规则：

- **不要**在业务代码中写死数字 `app_id`。
- 通过 `AppRegistryService` 用 `app_code` 解析出内部 `app_id`。
- 用户、会话、订单等表外键一律存 `app_id`。

## 为什么包名不能作为认证依据

Android 包名是公开元数据，可被伪造。

- `X-App-Code` **只能**用于应用路由与查找配置。
- **不能**作为认证依据，也不能信任客户端传入的 `app_id`。
- S2.1 起，以服务端签发会话中绑定的 `app_id` 为准。

## 复合外键隔离策略（S1.2）

`users` 表除主键 `id` 外，另有：

```sql
UNIQUE (app_id, id)
```

子表通过复合外键绑定「同一 App 下的用户」：

```sql
FOREIGN KEY (app_id, user_id) REFERENCES users (app_id, id)
```

效果：

| 场景 | 结果 |
|------|------|
| 微信身份 / 会话的 `app_id` 与用户 `app_id` 不一致 | **数据库拒绝** |
| 同一 OpenID 绑定到不同 App 的不同用户 | **允许** |
| 同一 App 内重复 `(wechat_appid, openid)` | **拒绝** |
| 同一 `device_key_hash` 在不同 App | **允许各一条试用** |
| 未知 `app_id` | **FK 拒绝** |

Repository 业务查询一律带 `appId`，例如：

- `UserRepository.findByAppIdAndId`
- `WechatIdentityRepository.findByAppIdAndWechatAppidAndOpenid`
- `AuthSessionRepository.findByAppIdAndTokenHash`
- `DeviceTrialRepository.findByAppIdAndDeviceKeyHash`

## 用户、会话、试用如何隔离

```text
users(id, app_id, ...)
wechat_identities(app_id, user_id, wechat_appid, openid, ...)
auth_sessions(app_id, user_id, token_hash, ...)
device_trials(app_id, device_key_hash, ...)
```

查询模板：

```sql
SELECT * FROM auth_sessions
WHERE token_hash = ? AND app_id = ?;  -- app_id 来自已认证上下文，而非客户端原样传入
```

同一微信用户在不同 App 拥有**不同**的内部 `users.id`。

## 跨 App 访问风险

1. 用 App A 的 Token 访问 App B 资源。  
2. 客户端篡改 `app_id` / `app_code`。  
3. 业务查询遗漏 `app_id`。  

缓解：复合 FK + 会话绑定 `app_id` + Repository 强制带 `appId` + 集成测试（见 `AppIsolationDataModelIT`）。

## 如何新增第二个 App

1. Flyway 插入 `apps` 行（新 `app_code` / `package_name`）。  
2. 为该 App 单独配置微信 `wechat_appid`、支付等（S2+）。  
3. 客户端使用自己的 `X-App-Code`；服务端解析为新 `app_id`。  
4. 新 App 的用户 / 试用与旧 App 自动隔离（表级约束已具备）。

## 如何验证跨 App 数据隔离

自动化：`AppIsolationDataModelIT`（真实 MySQL）。

人工（Navicat / 开发库）：

1. 插入第二个 `apps` 记录。  
2. 在 App A、App B 各建一个 `users`。  
3. 尝试用 App B 的 `app_id` + App A 的 `user_id` 插入 `wechat_identities` 或 `auth_sessions` → 应失败。  
4. 同一 `openid` 分别绑定两个 App → 应成功且 `user_id` 不同。

## 如何停用某个 App

```sql
UPDATE apps SET status = 'DISABLED' WHERE app_code = 'speaker_cleaner';
```

`AppRegistryService.requireActiveApp` 会返回 `APP_DISABLED`；历史行保留。

## 如何避免遗漏 `app_id` 查询条件

1. Repository 方法名带 `AppId`。  
2. 服务方法签名接收 `AppContext` / `Long appId`。  
3. 禁止仅用全局主键后“信任”结果。  
4. 评审清单检查是否按 `app_id` 过滤。
