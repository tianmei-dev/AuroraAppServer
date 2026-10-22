# 微信登录与会话（S2.1）

## 配置方法

按 `app_code` 配置微信开放平台移动应用凭证（**AppSecret 仅存服务端**）：

环境变量（推荐）：

```powershell
$env:WECHAT_APP_CALCULATOR_VAULT_APP_ID = "wx********"
$env:WECHAT_APP_CALCULATOR_VAULT_APP_SECRET = "********"
```

或写入 gitignore 的 `application-local.yml`：

```yaml
aurora:
  auth:
    session-ttl: 7d
  wechat:
    apps:
      calculator_vault:
        app-id: wx********
        app-secret: ********
      speaker_cleaner:
        app-id: wx********
        app-secret: ********
```

规则：

- 不在数据库明文保存 AppSecret。
- 缺少配置时返回 `WECHAT_CONFIG_MISSING`（50001），**不会**模拟登录成功。
- 日志不打印 AppSecret、授权 code、微信 access_token、会话明文 Token。

## API

### 登录

`POST /api/v1/auth/wechat/login`

```http
X-App-Code: calculator_vault
Content-Type: application/json

{"code":"微信临时授权code"}
```

成功响应 `data`：

```json
{
  "accessToken": "...",
  "tokenType": "Bearer",
  "expiresAt": "2026-10-29T12:00:00Z",
  "userId": 1,
  "appCode": "calculator_vault"
}
```

不返回：微信 access_token、AppSecret、OpenID/UnionID、会员状态。

### 当前用户

`GET /api/v1/auth/me`

```http
Authorization: Bearer {accessToken}
X-App-Code: calculator_vault
```

### 退出

`POST /api/v1/auth/logout`

```http
Authorization: Bearer {accessToken}
X-App-Code: calculator_vault
```

仅撤销当前会话；不删用户 / 微信身份 / 试用 / 会员 / 订单。

## Token 生命周期

1. 登录成功生成密码学安全随机 Token（32 字节，URL-Safe Base64）。
2. 数据库只存 SHA-256 十六进制哈希。
3. 默认有效期 **7 天**（`aurora.auth.session-ttl` 可配）。
4. 退出登录立即写 `revoked_at`。
5. 过期或已撤销 Token 不能访问受保护接口。
6. 本阶段无 Refresh Token。

## 多 App 隔离

- `X-App-Code` 仅用于路由与查配置，不是认证凭据。
- 用户与微信身份按 `app_id + wechat_appid + openid` 隔离。
- 同一 OpenID 在不同 App 可对应不同 `userId`。
- 会话绑定服务端可信 `app_id`；App A Token + App B 的 `X-App-Code` → `APP_CONTEXT_MISMATCH`。

## 常见错误码

| code | 常量 | 含义 |
|------|------|------|
| 40001 | APP_NOT_FOUND | 应用不存在 |
| 40002 | APP_DISABLED | 应用停用 |
| 40003 | APP_CONTEXT_REQUIRED | 缺少 X-App-Code |
| 40005 | WECHAT_CODE_INVALID | 授权 code 无效 |
| 40006 | WECHAT_AUTH_FAILED | 微信侧失败 / 网络异常 |
| 40100 | UNAUTHORIZED | 未认证 |
| 40101 | SESSION_EXPIRED | 会话过期 |
| 40102 | SESSION_REVOKED | 会话已撤销 |
| 40301 | APP_CONTEXT_MISMATCH | Token 与 X-App-Code 不一致 |
| 50001 | WECHAT_CONFIG_MISSING | 服务端缺少微信配置 |

## Android 后续接入（本 Task 不实现客户端）

1. 集成微信开放平台 Android SDK，取得临时 `code`。  
2. 调用本服务 `POST /api/v1/auth/wechat/login`，带上该 App 的 `X-App-Code`。  
3. 安全保存返回的 `accessToken`（建议系统 Keystore / EncryptedSharedPreferences）。  
4. 业务请求：`Authorization: Bearer ...` + `X-App-Code`。  
5. 退出时调用 `/auth/logout` 并清除本地 Token。

## 本地测试

自动化使用 Mock `WechatAuthClient`，不依赖真实微信 code：

```powershell
docker compose -f docker-compose.test.yml up -d
.\bootRun.bat   # 或 gradlew test
.\gradlew.bat test --tests com.aurora.appserver.auth.WechatAuthIT
```
