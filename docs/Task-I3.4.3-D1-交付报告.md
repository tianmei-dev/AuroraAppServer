# Task I3.4.3-D1 会员隔离联调环境交付报告

日期：2026-11-04  
状态：**已完成（停止，不进入 D2）**

## 1. 环境拓扑

```text
[保留] 8080 + profile local + aurora_app@3308     ← 微信联调 / 开发库（未改）
[本 Task] 8081 + profile s32it + aurora_app_test@3307
         ↑
   bootRun-s32it.bat（常驻）
   scripts/i343d1-fixture.bat seed|clean（短生命周期，server.port=0）
```

| 项 | 值 |
|---|---|
| 测试 Base URL | `http://127.0.0.1:8081`（真机/模拟器：`http://192.168.0.107:8081`） |
| App Code | `calculator_vault`（跨 App 负例：`i343d1_other_app`） |
| 会员查询 | `GET /api/v1/membership/me`（字段未改） |
| Grant HTTP | **无**（仍仅内部 Service） |

## 2. 修改文件

| 路径 | 说明 |
|---|---|
| `src/main/java/.../fixture/i343d1/*` | SafetyGate、MutableClock、FixtureService、Runner、Config |
| `scripts/i343d1-fixture.bat` | seed/clean CLI |
| `scripts/i343d1-verify-me.ps1` | 对常驻 8081 验 `/me`（不打印 Token） |
| `.gitignore` | 忽略 `.local/i343d1/sessions.local.json` |
| `docs/Task-I3.4.3-D1-交付报告.md` | 本报告 |

**未修改：** TEST 白名单、微信登录、Membership API 字段、支付模块、8080 配置、Android 客户端。

## 3. Profile 与数据库隔离证明

| 检查 | 结果 |
|---|---|
| 8081 日志 profile | `s32it` |
| 8081 JDBC | `jdbc:mysql://127.0.0.1:3307/aurora_app_test` |
| CLI SafetyGate | 要求 `s32it` + `SELECT DATABASE()=aurora_app_test`，否则立即失败 |
| 开发库 `aurora_app` | users=1，**membership_grants=0**，sessions=4（验收时） |
| 夹具范围 | 仅 `openid/source_ref LIKE i343d1-%`；不清理 IT 残留 |

## 4. Session 测试夹具说明

### 签发方式

1. `scripts\i343d1-fixture.bat seed`  
2. 在 **s32it** 下创建合成用户（openid=`i343d1-<label>`，非真实微信）  
3. `AuthSessionService.issueSession` 签发**真实** Bearer  
4. Token **只写入** `.local/i343d1/sessions.local.json`（gitignore）  
5. 元数据（无 Token）写入 `.local/i343d1/accounts.meta.json`  

**不会**打印 Token 到日志或本报告。  
**没有**测试登录 HTTP 接口。

### 账号标签（合成）

| label | openid | 期望 `/me` status |
|---|---|---|
| none | i343d1-none | NONE |
| monthly | i343d1-monthly | ACTIVE（MONTHLY） |
| yearly | i343d1-yearly | ACTIVE（YEARLY） |
| lifetime | i343d1-lifetime | ACTIVE + isLifetime |
| stack | i343d1-stack | ACTIVE（叠加，planType 可空） |
| expired | i343d1-expired | EXPIRED |
| revoked | i343d1-revoked | REVOKED |
| other-app-none | i343d1-other-app-none | NONE（`X-App-Code: i343d1_other_app`） |
| session-expired | （同 monthly 用户） | HTTP 401 / 40101 |
| session-revoked | （同 monthly 用户） | HTTP 401 / 40102 |

### 会员造数

- 一律 `MembershipService.grant/revoke` + `GrantSourceType.ADMIN`  
- `source_ref` 前缀 `i343d1-*`  
- **EXPIRED：** CLI 进程内 MutableClock 拨到 `2020-01-15T00:00:00Z` 后 grant MONTHLY，落库 `granted_at` 为过去；**常驻 8081 使用系统 UTC**，`/me` 返回 EXPIRED（已实测）  
- **未**直接改 membership 账本时间字段  

### 启停

```bat
REM 常驻隔离服务
bootRun-s32it.bat

REM 造数 / 清理（不占 8081）
scripts\i343d1-fixture.bat seed
scripts\i343d1-fixture.bat clean

REM 对 8081 验收（不打印 Token）
powershell -File scripts\i343d1-verify-me.ps1
```

清理只删 `i343d1-*` 行，不动其它 IT 数据。

## 5. 自动化 / 手工验收结果

对常驻 `http://127.0.0.1:8081` 执行 verify：

| 用例 | 结果 |
|---|---|
| none/monthly/yearly/lifetime/stack/expired/revoked/other-app | **PASS** |
| EXPIRED 以 `/me` 实返回为准 | **PASS**（status=EXPIRED） |
| session-expired → 40101 | **PASS** |
| session-revoked → 40102 | **PASS** |
| invalid token → 401 | **PASS** |
| 8080 仍 UP；开发库 grants=0 | **PASS** |
| 合计 | **PASS=11 FAIL=0** |

## 6. Android D2 交接说明

1. **服务地址：** `http://192.168.0.107:8081`（或本机模拟器 `10.0.2.2:8081`）  
2. **App Code：** `calculator_vault`  
3. **取 Session：** 在 Server 仓库运行 `scripts\i343d1-fixture.bat seed` 后，从本机 **`.local/i343d1/sessions.local.json`** 读取对应 label 的 `accessToken`（**勿提交、勿贴到聊天/报告**）  
4. **调用：** `GET /api/v1/membership/me` + `Authorization: Bearer …` + `X-App-Code`  
5. **注入方式：** 属 **Android D2**（不改 APK 的本 Task 范围外）；Server 只提供真实 Token 文件  
6. **grant/revoke/再 seed：** 仅通过 Server CLI；无生产 grant HTTP  
7. **清理：** `scripts\i343d1-fixture.bat clean`  

## 7. 风险与待解决问题

| 项 | 说明 |
|---|---|
| Token 文件安全 | 仅本机 gitignore；联调后建议 `clean` |
| ADMIN 语义 | 夹具用 ADMIN 而非 TEST；未放宽 TEST 白名单 |
| 8081 与 seed 并发 | seed 使用 `server.port=0`，不抢 8081 |
| Android Session 注入 | **D2 工作**；本 Task 不改客户端 |
| IT 脏库 | 仍有历史 IT 行；夹具前缀隔离 |

## 8. 停止声明

I3.4.3-D1 实施与验收完成，**不自动进入 D2**。等待审核。
