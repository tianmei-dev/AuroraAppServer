# Task S3.2 交付说明 · 设备试用期限不可延长

**状态：服务端已实现（方案 A）。**  
**范围：仅 AuroraAppServer；未改 Android；未部署生产；未进入 I3.1。**

---

## 1. 方案确认

采用评审结论 **方案 A**：

- **无数据库迁移**（复用 `device_trials.trial_expires_at`）
- 可选请求字段 `remainingSecondsCap`（0～259200）
- 缩短使用原子 SQL：`LEAST(trial_expires_at, candidate)`

---

## 2. 行为摘要

| 场景 | 行为 |
|------|------|
| 首次无 cap | `expires = now + 72h`，同事务写入 |
| 首次有 cap | `expires = min(now+72h, now+cap)`，同事务写入 |
| 已有记录无 cap | **不修改**到期（旧客户端兼容） |
| 已有记录有 cap | 原子 LEAST，只可提前 |
| 相同 cap、更晚请求 | 候选更晚 → 到期不变（不缩短、不延长） |
| 更小 cap 且候选更早 | 正确缩短 |
| 更大 cap、更晚请求 | 不得延长 |
| cap=0 | 立即过期；允许 `started == expires`；不可复活 |
| 并发首次 INSERT 冲突 | 恢复已有行后 **仍应用** 本请求 cap（不丢更严格上限） |
| 成功响应 | 基于重新读库的持久化结果；缩短失败则抛错，不返回成功 |

---

## 3. 修改文件

- `DeviceTrialActivateRequest` — 增加 `remainingSecondsCap`
- `DeviceTrialController` / `DeviceTrialService` / `DeviceTrialRepository`
- `ApiErrorCode.TRIAL_CAP_INVALID` (40010) + `GlobalExceptionHandler`
- `DeviceTrialIT` — S3.2 场景与并发/0秒/乱序/旧客户端等
- `docs/device-trial.md`、`docs/api-conventions.md`、`docs/S3.2-contract-review.md`（既有）
- 本文档

---

## 4. API 示例

```http
POST /api/v1/trial/device/activate
X-App-Code: calculator_vault
{"deviceKey":"...","remainingSecondsCap":86400}
```

错误：`remainingSecondsCap` ∉ [0,259200] → `40010 TRIAL_CAP_INVALID`。

---

## 5. Android 配合（本阶段不改）

1. 联网 activate 上报本地剩余秒作为 cap。  
2. 成功后用 Server `trialExpiresAt` 校准。  
3. **本地保留更严格约束**，不得因 Server 结果延长本地试用。  
4. 允许首包即 `EXPIRED` / `expires == started`。

---

## 6. 测试

`DeviceTrialIT` 覆盖：满 72h、离线 48h→24h、73h 不复活、cap=0、已过期不复活、无 cap 不延长、同 cap 更晚不变、更小 cap 可缩短、更大 cap 不延长、乱序、旧客户端、跨 App、账号切换无关、非法 cap、响应与库一致、并发创建+混合 cap、并发缩短、断网重试无新周期，以及原 S3.1 回归项。

### 回归命令

```powershell
# 需 Docker 测试库 3307（docker-compose.test.yml / aurora-mysql-it）
docker start aurora-mysql-it
.\gradlew.bat test
```

> 交付时若本机 Docker Desktop 未启动，集成测试会因 `Communications link failure` 失败；与 S3.2 逻辑无关。请在测试库就绪后执行全量 `test` 并确认 failures=0。

---

## 7. 已知边界

- 伪造新 `deviceKey` 仍可新开试用（既有信任模型）。  
- 旧客户端不传 cap 时，首次联网仍可能按联网时刻发满 72h；防离线消耗依赖新客户端上报 cap。  
- 不宣称绝对防重装。

---

## 8. 后续

- Android I3.1：对接 `remainingSecondsCap` 与本地更严约束。  
- 人工验收本 Server 变更后再合并/发版。
