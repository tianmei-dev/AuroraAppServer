# 架构总览（S1.1 / S1.2 / S2.1 / S3.1 / S4.1）

## 目标

用一套单体 Spring Boot 服务，支撑多个 Android App 的共用业务能力。  
账号、会员、设备试用、订单等数据按服务端内部 `app_id` 严格隔离。

## 方案选择

**方案 A：各 App 账号独立。**

## 运行形态

```
Android App A ──┐
Android App B ──┼──► AuroraAppServer ──► MySQL aurora_app
Android App C ──┘         │
                          ├── /auth/wechat/login（会话）
                          ├── /trial/device/activate（游客试用）
                          ├── /membership/me（会员查询，需登录）
                          └── /actuator/health
```

## 包职责

| 包 | 职责 | 状态 |
|----|------|------|
| `common` | 响应 / 错误码 / Clock | S1.1～S4.1 |
| `app` | 应用注册 | S1.1 |
| `security` | 安全过滤器 | S1.1～S4.1 |
| `auth` | 微信登录与会话 | S2.1 |
| `trial` | 72h 设备试用 | S3.1 |
| `membership` | **会员套餐 / 发放账本 / 状态查询** | **S4.1** |
| `payment` | 订单与支付 | 未实现 |

## S4.1 边界

已完成：套餐种子、grant/revoke 内部服务、叠加与永久优先、幂等并发、`GET /membership/me`。

**未完成：** 订单、支付回调、退款流程、管理后台、Android 会员接入、试用与 VIP 联合判定。

## 关键原则

1. `X-App-Code` / `deviceKey` 不是强认证。  
2. 原始 deviceKey / HMAC 密钥不入日志、不入库明文。  
3. 试用与会员时间只由服务端 Clock 决定。  
4. 不宣称绝对防重装重置试用。  
5. 会员发放仅内部可信来源；客户端不可指定金额/时长/到期时间。  
