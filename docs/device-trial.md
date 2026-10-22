# 设备 72 小时免费试用（S3.1）

## 产品规则

1. 每台设备在每个 App 中仅有一次连续 **72 小时**免费试用。  
2. 试用从**首次打开 App**（服务端首次登记）开始计算。  
3. 未登录游客也可领取。  
4. 关闭 App / 退出登录 / 重启**不暂停**计时。  
5. 到期后不可重新领取；不延长、不重置。  
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

{"deviceKey":"Android端应用范围标识"}
```

成功：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "status": "ACTIVE",
    "trialStartedAt": "2026-01-01T00:00:00Z",
    "trialExpiresAt": "2026-01-04T00:00:00Z",
    "serverTime": "2026-01-01T00:00:00Z",
    "remainingSeconds": 259200
  }
}
```

到期时 `status=EXPIRED`，`remainingSeconds=0`。

不返回：`device_key_hash`、内部 id、其它 App 数据。

客户端**不能**指定开始/结束时间或状态（多余字段忽略，服务端时间以 UTC Clock 为准）。

## HMAC 配置

```yaml
aurora:
  trial:
    hmac-secret: ${TRIAL_HMAC_SECRET}
    duration: 72h
    rate-limit-per-window: 30
    rate-limit-window: 1m
```

哈希输入（无歧义）：

```text
HMAC-SHA256(secret, UTF8(app_code) || 0x00 || UTF8(deviceKey)) → hex
```

- 密钥不得写入源码或提交 Git。  
- 缺密钥 → `TRIAL_CONFIG_MISSING` (50002)。  
- **密钥轮换**会使旧 `deviceKey` 映射到新哈希，表现为“新设备”；本 Task 不实现轮换系统，运维需谨慎更换密钥。

`deviceKey` 校验：长度 8–128，字符集 `[A-Za-z0-9._\-:]+`。

`X-App-Code` / `deviceKey` **不是**不可伪造的设备认证凭据。

## 幂等与并发

- `UNIQUE(app_id, device_key_hash)` 保证同一 App 同一哈希仅一条。  
- 首次创建使用 `REQUIRES_NEW`；唯一约束冲突后在**新查询**中恢复原记录（不复用失败事务）。  
- 重复请求返回原 `trial_started_at` / `trial_expires_at`。

## 匿名限流

进程内滑动窗口（**无 Redis**）：

- 桶：`trial:{appCode}:{clientIp}`  
- 默认：每分钟 30 次（可配）  
- 超限：`TRIAL_RATE_LIMITED` (42901)  

限流只能减轻滥用，**不能**阻止伪造新的 `deviceKey`。  
IP 不作设备唯一身份。

## 离线试用衔接（Android）

1. 离线首次打开可先建**本地临时**试用。  
2. 首次联网调用本接口；服务端以**自己的首次登记时间**为准。  
3. 已有服务端记录时，优先用服务端开始/结束时间。  
4. 离线本地计时与服务端首次登记时间**可能不一致**；不承诺两者 72 小时窗口完全重合。  

联调建议：启动后尽早联网 activate；本地以服务端返回的 `trialExpiresAt` / `serverTime` 校准。

## 与会员关系（预留）

有效 VIP > 有效设备试用 > 免费功能。  

退出微信：不删试用、不重领；仅停止该账号 VIP（会员系统未实现）。

## 错误码

| code | 常量 |
|------|------|
| 40001 | APP_NOT_FOUND |
| 40002 | APP_DISABLED |
| 40003 | APP_CONTEXT_REQUIRED |
| 40007 | DEVICE_KEY_INVALID |
| 42901 | TRIAL_RATE_LIMITED |
| 50002 | TRIAL_CONFIG_MISSING |

## Android 下一步对接

1. 生成应用范围 `deviceKey`（勿用 IMEI/MAC）。  
2. 启动时（可游客）调用 `POST /api/v1/trial/device/activate`。  
3. 缓存 `trialExpiresAt` / `remainingSeconds`，用 `serverTime` 校时。  
4. 权益判断：VIP（后续）优先于试用状态。  
