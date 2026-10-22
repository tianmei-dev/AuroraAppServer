# 架构总览（S1.1 / S1.2 / S2.1）

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
Android App C ──┘         │                      ▲
                          └── Actuator /health    │
                                                  WeChat OAuth (服务端换 code)
```

## 包职责

| 包 | 职责 | 状态 |
|----|------|------|
| `common` | 统一响应 / 错误码 / 异常处理 | S1.1 |
| `app` | 应用注册与 `AppContext` | S1.1 |
| `security` | SecurityFilterChain + Bearer 过滤器 | S1.1 + **S2.1** |
| `auth` | 用户 / 微信身份 / 会话 + **登录 API** | S1.2 + **S2.1** |
| `trial` | 设备试用表 | S1.2（无计时业务） |
| `membership` / `payment` | 预留 | 未实现 |

## S2.1 边界

已完成：

- 按 App 的微信配置注入（环境变量 / local yml）
- `WechatAuthClient` 正式实现 + 测试 Mock
- `POST /api/v1/auth/wechat/login`、`GET /me`、`POST /logout`
- 不透明会话 Token（仅存哈希）、过期与撤销
- 会话 `app_id` 与 `X-App-Code` 一致性校验
- 并发首次登录防重复身份

**未完成：** Refresh Token、试用计时、会员、支付、Android SDK、管理后台。

## 关键原则

1. 显式传递 `AppContext` / `appId`。  
2. `X-App-Code` 不是认证凭据；认证以会话为准。  
3. AppSecret / code / 微信 access_token / 明文会话 Token 不写日志、不回传多余字段。  
4. Schema 仅 Flyway；无假登录后门。  
