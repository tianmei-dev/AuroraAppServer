# 设备 72 小时免费试用（S3.1 / S3.2）

## 产品规则

1. 每台设备在每个 App 中仅有一次连续 **72 小时**免费试用。  
2. 试用权威到期以服务端 `trial_expires_at` 为准；**只可保持或提前，不可延长**（S3.2）。  
3. 未登录游客也可领取。  
4. 关闭 App / 退出登录 / 重启**不暂停**计时。  
5. 到期后不可重新领取；不延长、不重置、不复活。  
6. 同一设备切换微信账号**不重置**试用。  
7. 不同 App 独立试用。  
8. **不存储**原始 `deviceKey`，只存 HMAC 哈希。  

> 普通 Android 无法在卸载重装、清数据、系统重置后保证识别同一物理设备。  
> **本服务不宣称绝对防重装重置试用。**

## API

`POST /api/v1/trial/device/activate`（匿名，需 `X-App-Code`）

```http
X-App-Code: calculator_vault
Content-Type: application/json

{"deviceKey":"Android端应用范围标识","remainingSecondsCap":86400}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `deviceKey` | 是 | 与 S3.1 相同 |
| `remainingSecondsCap` | 否 | 0～259200（秒）。缺省 = 旧客户端兼容 |

### `remainingSecondsCap`（S3.2）

- 候选到期：`serverNow + remainingSecondsCap`。  
- **首次**：在同一事务写入 `min(now+72h, candidate)`（无 cap 则 `now+72h`）。  
- **已有记录**：无 cap → **不改**到期；有 cap → 原子 `LEAST(trial_expires_at, candidate)`。  
- `0`：立即过期（允许 `trialExpiresAt == trialStartedAt`），之后不可复活。  
- 相同 cap 在更晚的 `serverNow` 下候选到期更晚，`LEAST` **不会缩短也不会延长**已有到期。  
- 仅当后续更小的 cap 使 `now+cap` 早于已有 `trial_expires_at` 时才会缩短。  
- Server **不信任**客户端绝对首次启动时间。

成功：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "status": "ACTIVE",
    "trialStartedAt": "2026-01-01T00:00:00Z",
    "trialExpiresAt": "2026-01-02T00:00:00Z",
    "serverTime": "2026-01-01T00:00:00Z",
    "remainingSeconds": 86400
  }
}
```

- 成功响应中的 `trialExpiresAt` **必须**与已持久化结果一致；事务失败不得返回成功。  
- 到期时 `status=EXPIRED`，`remainingSeconds=0`。  
- 不返回：`device_key_hash`、内部 id。

客户端**不能**指定开始/结束时间或状态（多余字段忽略）。

## HMAC 配置

```yaml
aurora:
  trial:
    hmac-secret: ${TRIAL_HMAC_SECRET}
    duration: 72h
    rate-limit-per-window: 30
    rate-limit-window: 1m
```

哈希输入：

```text
HMAC-SHA256(secret, UTF8(app_code) || 0x00 || UTF8(deviceKey)) → hex
```

`deviceKey`：长度 8–128，字符集 `[A-Za-z0-9._\-:]+`。

## 幂等与并发

- `UNIQUE(app_id, device_key_hash)`。  
- 首次：`REQUIRES_NEW` INSERT（含最终 expires）；唯一约束冲突后 **必须**对已有行再应用本次 cap（不可丢弃更严格上限）。  
- 缩短：原生 SQL `UPDATE ... SET trial_expires_at = LEAST(...)`。  
- 无 cap 的重复请求不修改到期。

## 匿名限流

进程内滑动窗口（无 Redis）：`trial:{appCode}:{clientIp}`，默认 30/分钟 → `TRIAL_RATE_LIMITED` (42901)。

## 离线试用衔接（Android）

1. 离线可建本地临时试用。  
2. 首次联网上报 `remainingSecondsCap = min(本地剩余秒, 259200)`。  
3. 成功后以服务端 `trialExpiresAt` / `serverTime` 校准。  
4. **本地须保留更严格约束**：不得因服务端结果无条件把本地试用延长。  
5. 本地已用尽传 `0`。

## 错误码

| code | 常量 |
|------|------|
| 40001 | APP_NOT_FOUND |
| 40002 | APP_DISABLED |
| 40003 | APP_CONTEXT_REQUIRED |
| 40007 | DEVICE_KEY_INVALID |
| 40010 | TRIAL_CAP_INVALID |
| 42901 | TRIAL_RATE_LIMITED |
| 50002 | TRIAL_CONFIG_MISSING |

## 与会员关系

有效 VIP > 有效设备试用 > 免费功能。  
退出微信不删试用记录。
