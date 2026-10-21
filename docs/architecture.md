# 架构总览（S1.1 / S1.2）

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
- 暂不引入 Redis。

## 包职责

| 包 | 职责 | 状态 |
|----|------|------|
| `common` | `ApiResponse` / `ApiErrorCode` / `GlobalExceptionHandler` | S1.1 已实现 |
| `app` | `apps` 注册表、`AppRegistryService`、`AppContext` | S1.1 已实现 |
| `security` | 默认拒绝、健康检查匿名 | S1.1 已实现 |
| `auth` | `users` / `wechat_identities` / `auth_sessions` 实体与 Repository | **S1.2 数据模型**（无登录 API） |
| `trial` | `device_trials` 实体与 Repository | **S1.2 数据模型**（无试用计时） |
| `membership` | 会员 | 预留 |
| `payment` | 第三方聚合支付 | 预留 |

## S1.2 边界

已完成：表结构、JPA 映射、按 `app_id` 查询的 Repository、约束与隔离测试。

**未完成（留给 S2.1+）：**

- 微信 OAuth / Token 签发与刷新
- 设备标识采集与 72 小时试用计算
- 任何业务 HTTP API

## 关键原则

1. **显式传递 `AppContext` / `appId`**，优先于 ThreadLocal。  
2. 客户端 `X-App-Code` / 包名不是安全凭证。  
3. 受保护业务必须以服务端会话中绑定的 `app_id` 为准（S2.1）。  
4. Schema 变更只通过 Flyway；JPA `ddl-auto=validate`。  
5. 用户—身份 / 用户—会话 通过复合外键 `(app_id, user_id)` 防跨 App 串数据。

## 配置分层

| 文件 | 用途 |
|------|------|
| `application.yml` | 公共默认配置（无真实密码） |
| `application-local.yml` | 本地凭据（gitignore） |
| `application-local.yml.example` | 安全示例（开发库端口 3308） |
| `application-test.yml` | 测试配置（`docker-compose.test.yml` 端口 3307） |
| `docker-compose.dev.yml` | 本地开发 MySQL |
| `docker-compose.test.yml` | 集成测试 MySQL |
