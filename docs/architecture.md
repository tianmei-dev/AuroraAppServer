# 架构总览（S1.1）

## 目标

用一套单体 Spring Boot 服务，支撑多个 Android App 的共用业务能力。  
账号、会员、设备试用、订单等数据按服务端内部 `app_id` 严格隔离。

## 方案选择

**方案 A：各 App 账号独立。**

- 同一自然人在不同 App 中是不同用户。
- 不共享会员、不共享订单、不共享试用额度。
- 微信登录配置、支付商户配置均按 App 管理。

## 运行形态

```
Android App A ──┐
Android App B ──┼──► AuroraAppServer (单体) ──► MySQL aurora_app
Android App C ──┘         │
                          └── Actuator /health
```

- 不使用微服务、Kubernetes、复杂多租户框架。
- S1.1 不引入 Redis。

## 包职责

| 包 | 职责 | S1.1 状态 |
|----|------|-----------|
| `common` | `ApiResponse` / `ApiErrorCode` / `GlobalExceptionHandler` | 已实现 |
| `app` | `apps` 注册表、`AppRegistryService`、`AppContext` | 已实现 |
| `security` | 默认拒绝、健康检查匿名 | 已实现 |
| `auth` | 账号认证 | 预留，未实现 |
| `trial` | 设备试用 | 预留，未实现 |
| `membership` | 会员 | 预留，未实现 |
| `payment` | 第三方聚合支付 | 预留，未实现 |

## 关键原则

1. **显式传递 `AppContext` / `appId`**，优先于 ThreadLocal。
2. 若使用 ThreadLocal，必须在请求结束清理，并避免在异步线程泄漏。
3. 客户端 `X-App-Code` / 包名仅用于路由与配置查找，不是安全凭证。
4. 受保护业务（S2+）必须以服务端会话中绑定的 `app_id` 为准。
5. Schema 变更只通过 Flyway；JPA `ddl-auto=validate`。

## 配置分层

| 文件 | 用途 |
|------|------|
| `application.yml` | 公共默认配置（无真实密码） |
| `application-local.yml` | 本地凭据（gitignore） |
| `application-local.yml.example` | 安全示例 |
| `application-test.yml` | 测试配置（配合 `docker-compose.test.yml` 隔离库） |
