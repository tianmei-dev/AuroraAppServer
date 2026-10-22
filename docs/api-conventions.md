# API 约定

## 前缀

所有业务 API 使用：

```text
/api/v1
```

除明确放行的接口外，`/api/v1/**` 需认证。

匿名放行：

- `POST /api/v1/auth/wechat/login`（需 `X-App-Code` + 微信配置）
- `POST /api/v1/trial/device/activate`（需 `X-App-Code` + 试用 HMAC 配置；游客可访问）

需登录：

- `GET /api/v1/membership/me`（S4.1；会员状态；**无**公开 grant/revoke HTTP）

## 统一响应

成功：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

失败：

```json
{
  "code": 40001,
  "message": "错误描述",
  "data": null
}
```

实现类：

- `ApiResponse`
- `ApiErrorCode`
- `GlobalExceptionHandler`

## 错误码分段（稳定可扩展）

| 范围 | 用途 |
|------|------|
| `0` | 成功 |
| `40000+` | 客户端 / 应用上下文 |
| `40100+` | 认证（S2+） |
| `40300+` | 授权（S2+） |
| `40400+` | 资源不存在 |
| `50000+` | 服务端错误 |

已定义（含 S2.1）：

| code | 常量 | 含义 |
|------|------|------|
| 0 | `SUCCESS` | 成功 |
| 40001 | `APP_NOT_FOUND` | 应用不存在 |
| 40002 | `APP_DISABLED` | 应用已停用 |
| 40003 | `APP_CONTEXT_REQUIRED` | 缺少应用上下文 |
| 40004 | `VALIDATION_FAILED` | 参数校验失败 |
| 40005 | `WECHAT_CODE_INVALID` | 微信 code 无效 |
| 40006 | `WECHAT_AUTH_FAILED` | 微信认证失败 |
| 40007 | `DEVICE_KEY_INVALID` | 设备标识非法 |
| 40100 | `UNAUTHORIZED` | 未认证 |
| 40101 | `SESSION_EXPIRED` | 会话过期 |
| 40102 | `SESSION_REVOKED` | 会话已撤销 |
| 40300 | `FORBIDDEN` | 无权限 |
| 40301 | `APP_CONTEXT_MISMATCH` | Token 与 X-App-Code 不一致 |
| 40400 | `NOT_FOUND` | 路由/资源不存在 |
| 50000 | `INTERNAL_ERROR` | 内部错误（不返回堆栈） |
| 40008 | `MEMBERSHIP_PLAN_DISABLED` | 会员套餐已禁用 |
| 40009 | `MEMBERSHIP_GRANT_SOURCE_INVALID` | 发放来源非法 |
| 40302 | `MEMBERSHIP_TEST_SOURCE_FORBIDDEN` | 生产环境禁止 TEST 发放 |
| 40401 | `MEMBERSHIP_PLAN_NOT_FOUND` | 套餐不存在 |
| 40402 | `MEMBERSHIP_GRANT_NOT_FOUND` | 发放记录不存在 |
| 42901 | `TRIAL_RATE_LIMITED` | 试用接口限流 |
| 50001 | `WECHAT_CONFIG_MISSING` | 缺少微信配置 |
| 50002 | `TRIAL_CONFIG_MISSING` | 缺少试用 HMAC 配置 |

试用见 [device-trial.md](device-trial.md)；会员见 [membership.md](membership.md)。

## 应用标识与会话请求头

```http
X-App-Code: calculator_vault
Authorization: Bearer {accessToken}
```

注意：

- `X-App-Code` 仅用于路由与配置查找，**不是**认证凭据。
- 受保护接口必须同时校验 Bearer 会话中的 `app_id` 与 `X-App-Code` 解析结果一致。
- 服务端不得直接信任客户端传入的数字 `app_id` / `userId`。
- 详情见 [wechat-login.md](wechat-login.md)。

## 健康检查

```http
GET /actuator/health
```

- 允许匿名
- 仅返回基础状态（如 `{"status":"UP"}`）
- 不暴露数据库凭据、密钥、Token、内部配置
- 不开放全部 Actuator 端点

## 安全策略（S1.1）

1. 默认拒绝未经授权的业务请求。
2. 无测试管理员账号、无固定访问 Token、无开发后门。
3. 不提供公开的应用注册 / 修改 / 删除接口。
4. 不存在的 API 返回合理 404（或未认证时的 401），不暴露内部实现细节。
