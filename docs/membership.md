# 会员权益（S4.1）

## 目标

多 App 共用、数据严格按 `app_id` 隔离的会员权益基础能力。  
**Server 为权威来源**；本 Task 不接入支付、不提供公开发放/撤销 HTTP API。

## 套餐（CalculatorVault 种子）

| plan_code | 展示名 | 价格（分） | 时长 |
|-----------|--------|------------|------|
| MONTHLY | 月卡 | 590（¥5.9） | 30 × 24h（非自然月） |
| YEARLY | 年卡 | 1990（¥19.9） | 365 × 24h（非自然年） |
| LIFETIME | 永久会员 | 2990（¥29.9） | 永久（`is_lifetime=1`，无到期） |

规则：一次性购买、不自动续费；套餐与 App 绑定，客户端不可指定金额/时长/等级。

## 数据模型

```text
apps
  └── membership_plans (app_id)
users
  ├── membership_grants (app_id, user_id)      -- 发放账本（不物理删除）
  └── membership_entitlements (app_id, user_id) -- 行锁 + 物化摘要
```

- **plans**：按 App 定义套餐；`UNIQUE (app_id, plan_code)`。
- **grants**：每笔发放一条；`UNIQUE (app_id, source_type, source_ref)` 保证幂等；可空 `order_id` 预留订单关联；撤销只写 `revoked_at`。
- **entitlements**：每用户一行，发放/撤销时 `SELECT … FOR UPDATE`，防止并发丢权益。

复合外键 `(app_id, user_id) → users(app_id, id)`，禁止跨 App 挂错用户。

## 状态

| status | 含义 |
|--------|------|
| NONE | 从未有过发放记录 |
| ACTIVE | 当前有效（含永久） |
| EXPIRED | 有未撤销的限时权益，但已到期 |
| REVOKED | 曾有发放，但当前全部已撤销 |

有效性一律用服务端 UTC `Clock`，不信任客户端时间。

## 叠加算法（账本重放）

对未撤销 grants 按 `granted_at ASC, id ASC` 重放：

1. 若存在未撤销 **LIFETIME** → `ACTIVE` + `isLifetime=true`，`expiresAt=null`（永久优先）。
2. 否则对限时权益：
   - 无当前覆盖、或 `granted_at >= currentEnd` → 从 `granted_at` 新开窗口；
   - 否则在 `currentEnd` 上累加 `duration_days`。
3. 撤销一笔后重新重放剩余 grants，不整号清零。

因此：月卡顺延、月+年叠加、到期后再买从新发放时间起算、撤销叠加中的一笔可正确回算。

## 发放 / 撤销（内部 Service）

```text
MembershipService.grant(GrantCommand)
MembershipService.revoke(appId, sourceType, sourceRef, reason)
MembershipService.getStatus(appId, userId)
```

来源：`PAYMENT` | `ADMIN` | `PROMOTION` | `TEST`  
`TEST` 仅允许 `test` / `local` / `dev` profile。

幂等：同一 `(app_id, source_type, source_ref)` 重复 grant / revoke 不重复加时长、不报错。  
并发：entitlement 悲观锁 + DB 唯一约束；不同订单并发均可落账。

## 查询 API

```http
GET /api/v1/membership/me
X-App-Code: calculator_vault
Authorization: Bearer <session_token>
```

必须登录；用户与 App 来自会话；`X-App-Code` 须与 Token 所属 App 一致。

响应字段要点：

- `planType`：仅当单一套餐类型可完整描述有效权益时返回；叠加时为 `null`。
- `contributingPlanTypes`：参与当前有效计算的套餐类型列表。
- `isLifetime` / `membershipStartedAt` / `membershipExpiresAt` / `serverTime` / `activeGrantCount`。

无公开 `/grant`、`/revoke` 路由。

## 与设备试用

S4.1 不修改试用。后续产品优先级：**有效 VIP > 有效设备试用 > 免费**。  
退出登录不删除会员库记录；重新登录可恢复。

## 后续支付如何调用

支付回调验单成功后：

```java
membershipService.grant(new GrantCommand(
    appId, userId, PlanCode.YEARLY,
    GrantSourceType.PAYMENT,
    orderId,          // 幂等键
    orderId           // order_id 预留
));
```

退款：`revoke(appId, PAYMENT, orderId, "refund")`，服务端重算剩余权益。
