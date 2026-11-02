# Task · 微信登录正式配置检查

**日期：** 2026-10-29  
**范围：** CalculatorVault（`app_code=calculator_vault`）微信开放平台移动应用联调前置检查  
**约束：** 未改设备试用 S3.2、会员、支付；未部署生产；本文不包含 AppSecret 明文。

---

## 1. 结论摘要

| 项 | 状态 |
|----|------|
| 服务端换码 / 建用户 / 发会话实现 | **已具备**（S2.1） |
| AppSecret 注入方式（env / local yml，gitignore） | **正确，无硬编码进 Git** |
| 正式 AppID `wxa4c721130ed106c9` 是否已写入本机运行配置 | **未配置** |
| 本机 AppSecret 是否已注入 | **未配置**（当前环境变量与 `application-local.yml` 均无微信凭证） |
| 自动化测试（auth 包） | **通过** |
| **是否已具备真机联调条件** | **否** — 需人工完成本机 AppID/AppSecret 配置后再联调 |

代码路径已就绪；**缺密钥即无法用真实微信 code 登录**（会返回 `WECHAT_CONFIG_MISSING` / 50001）。

---

## 2. AppID 配置方式

配置键（按 `app_code`）：

```yaml
# application.yml（已提交，仅占位）
aurora:
  wechat:
    apps:
      calculator_vault:
        app-id: ${WECHAT_APP_CALCULATOR_VAULT_APP_ID:}
        app-secret: ${WECHAT_APP_CALCULATOR_VAULT_APP_SECRET:}
```

解析：`WechatConfigService.requireCredentials("calculator_vault")` → 缺省或空 → `WECHAT_CONFIG_MISSING`。

### 人工配置（任选其一）

**推荐 — 环境变量（PowerShell 当前会话）：**

```powershell
$env:WECHAT_APP_CALCULATOR_VAULT_APP_ID = "wxa4c721130ed106c9"
$env:WECHAT_APP_CALCULATOR_VAULT_APP_SECRET = "<微信开放平台控制台中的 AppSecret>"
```

**或 — gitignore 的 `src/main/resources/application-local.yml`：**

```yaml
aurora:
  wechat:
    apps:
      calculator_vault:
        app-id: wxa4c721130ed106c9
        app-secret: <AppSecret>
```

也可在该文件中继续用 `${WECHAT_APP_CALCULATOR_VAULT_APP_ID}` / `${WECHAT_APP_CALCULATOR_VAULT_APP_SECRET}` 引用环境变量。

`.env.example` 已列出同名变量注释；`.env` / `application-local.yml` 均在 `.gitignore` 中。

### 当前本机抽查（不读 Secret 明文）

| 来源 | 结果 |
|------|------|
| 进程环境变量 `WECHAT_APP_CALCULATOR_VAULT_*` | 未设置 |
| `application-local.yml` | 存在且 gitignore；**仅有数据源与 trial HMAC，无 `aurora.wechat` 段** |
| 已提交源码中是否出现正式 AppID / Secret | **未发现硬编码 Secret**；正式 AppID 亦未写进仓库配置 |

---

## 3. AppSecret 安全性

| 检查项 | 结果 |
|--------|------|
| Git 跟踪的 yml 是否含真实 Secret | 否（仅 `${ENV:}` 或 example 占位） |
| `application-local.yml` / `.env` 是否被 ignore | 是 |
| HTTP 客户端是否打日志 Secret / code / access_token | 否（`HttpWechatAuthClient` 仅打 errcode / 异常类型） |
| Secret 是否入库 | 否（仅内存配置；身份表存 `wechat_appid` + openid，不存 secret） |

---

## 4. Android code 换取微信身份流程（服务端）

```text
Android SDK 取得 code
    → POST /api/v1/auth/wechat/login
         Header: X-App-Code: calculator_vault
         Body: { "code": "..." }
    → WechatLoginService
         1. requireActiveApp(app_code)
         2. requireCredentials(calculator_vault)  // AppID + Secret
         3. HttpWechatAuthClient.exchangeCode
              GET https://api.weixin.qq.com/sns/oauth2/access_token
                  ?appid=&secret=&code=&grant_type=authorization_code
              解析 openid / unionid（可选）
         4. 按 (app_id, wechat_appid, openid) 查/建 users + wechat_identities
         5. AuthSessionService.issueSession → 返回 Bearer accessToken
```

超时：连接 5s、读取 10s；网络/解析失败 → `WECHAT_AUTH_FAILED` (40006)。

---

## 5. 与 users / wechat_identities / auth_sessions 关联

| 步骤 | 行为 |
|------|------|
| 已有身份 | `findByAppIdAndWechatAppidAndOpenid` → 加载同 App 下 `users` |
| 新用户 | `REQUIRES_NEW`：建 `users(ACTIVE)` + `wechat_identities`（复合 FK） |
| 并发首登 | 唯一约束冲突后重查，不复用失败事务 |
| 会话 | `auth_sessions` 绑定 `(app_id, user_id)`，仅存 token 哈希，TTL 默认 7d |
| 隔离 | 同一 openid 跨 App 可对应不同 user；Token 与 `X-App-Code` 不一致 → `APP_CONTEXT_MISMATCH` |

退出登录只撤销会话，不删用户/微信身份/试用/会员。

---

## 6. 错误处理

| 场景 | 错误码 | 说明 |
|------|--------|------|
| 缺 AppID/Secret | 50001 `WECHAT_CONFIG_MISSING` | 当前本机未配密钥时的预期 |
| code 空 | 40004 `VALIDATION_FAILED` | |
| 微信 errcode 40029 / 40163 / 42001 / 40055 | 40005 `WECHAT_CODE_INVALID` | 含无效、**已使用**、过期类 code |
| 其他微信 errcode / 无 openid | 40006 `WECHAT_AUTH_FAILED` | |
| HTTP 超时 / 网络异常 | 40006 `WECHAT_AUTH_FAILED` | |
| App 不存在 / 禁用 | 40001 / 40002 | |
| 用户 DISABLED | 40300 `FORBIDDEN` | |

自动化覆盖：无效 code、微信失败、缺配置、跨 App、会话过期/撤销等（`WechatAuthIT`，使用 Mock，不调真微信）。

---

## 7. 测试结果

```text
.\gradlew.bat test --tests "com.aurora.appserver.auth.*"
→ BUILD SUCCESSFUL
```

auth 相关套件（含 `WechatAuthIT`、`AppIsolationDataModelIT`、`AccountAndTrialSchemaIT`）执行通过。  
（Mock 客户端，不验证真实 AppID/Secret。）

未改动试用 / 会员 / 支付代码。

---

## 8. 真机联调就绪清单

完成下列人工步骤后，**即可**与已审核通过的移动应用联调：

1. 在本机设置  
   - `WECHAT_APP_CALCULATOR_VAULT_APP_ID=wxa4c721130ed106c9`  
   - `WECHAT_APP_CALCULATOR_VAULT_APP_SECRET=<控制台 Secret>`  
   或写入 gitignore 的 `application-local.yml`（同上）。  
2. 确认开发库 MySQL（如 3308）与 `bootRun` / 本地服务可启动。  
3. Android：微信 SDK 签名/包名与开放平台一致；登录后把 `code` 打到  
   `POST /api/v1/auth/wechat/login` + `X-App-Code: calculator_vault`。  
4. 验证：返回 `accessToken`；`GET /api/v1/auth/me` 成功；DB 出现对应 `users` / `wechat_identities` / `auth_sessions`。

**当前阻塞点：** 本机尚未注入正式 AppID 与 AppSecret → **暂不具备真机联调条件**。

---

## 9. 风险与注意

- 微信 `code` 一次性；客户端重试同一 code → `WECHAT_CODE_INVALID`。  
- 密钥轮换后需同步更新本机 env / local yml。  
- 测试 profile 仍使用假 AppID（`wx_test_calculator`），与正式凭证隔离，勿混用测试库配置做真机联调。
