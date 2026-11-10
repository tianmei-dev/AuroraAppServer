# paystaging 部署与运维说明（悦森真实支付联调环境）

适用范围：I3.5.5-B1 及之后的 STAGING 真实支付联调。
**本文档不包含任何真实凭证。**所有 `<…>` 都是占位符，必须由人工在部署时填写。

---

## 0. 隔离保证（代码强制执行，不依赖本文档）

只要启用了 `paystaging` profile，`PaystagingIsolationRules` 就会在**创建 Spring 上下文和任何数据库连接之前**检查以下各项，任何一项不满足都会拒绝启动：

| 规则 | 要求 |
|---|---|
| 数据库 | JDBC URL 中的库名必须**恰好**是 `aurora_app_paystaging`；`aurora_app` 和 `aurora_app_test` 会被明确拒绝 |
| 支付渠道 | `YUSEN`。FAKE 或 NONE 都会被拒绝 |
| 支付环境 | `STAGING` |
| 端口 | 不能是 8080 或 8081；不配置时 Spring 默认为 8080，同样会被拒绝 |
| profile 组合 | 不能与 `s32it`、`test`、`local` 同时启用 |
| 商户配置 | `YUSEN_PID`、`YUSEN_KEY`、`YUSEN_GATEWAY_URL`、`YUSEN_NOTIFY_URL`、`YUSEN_RETURN_URL` 必须齐全；三个 URL 必须是 **https 加公网主机名**（拒绝 localhost、127、10、172.16–31、192.168、`.local`、`.lan`、`.internal`、`.test` 等） |
| 告警 Webhook | 如果配置了，必须是 https |

此外还有两层运行时检查：

- **Flyway 迁移之前**，对实际连接执行 `SELECT DATABASE()`，结果必须是 `aurora_app_paystaging`；启动完成时再查一次。
- `PaymentConfigurationGuard`：STAGING 环境禁止出现 Fake bean；选用悦森时三个 URL 必须是 https；TEST 环境禁止开启外发网络。

这些规则是写在代码里的，**不能通过修改配置绕过**。生产 jar 中也不包含 Fake 渠道和测试夹具。

---

## 1. 环境变量清单

| 变量 | 必填 | 默认值 | 是否机密 | 说明 |
|---|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | 是 | — | 否 | 只能是 `paystaging`，不要追加其他 profile |
| `PAYSTAGING_DB_HOST` | 是 | 无 | 否 | 联调库所在的主机 |
| `PAYSTAGING_DB_PORT` | 否 | `3306` | 否 | |
| `PAYSTAGING_DB_USERNAME` | 是 | 无 | 中 | 只对 `aurora_app_paystaging` 有权限的专用账号 |
| `PAYSTAGING_DB_PASSWORD` | 是 | 无 | **是** | |
| `PAYSTAGING_DB_SSL_MODE` | 否 | `REQUIRED` | 否 | 数据库不支持 TLS 时需要人工评估，不建议降级 |
| `PAYSTAGING_PORT` | 否 | `8082` | 否 | 应用监听端口，只监听内网，由反向代理对外 |
| `YUSEN_PID` | 是 | 无 | 中 | 悦森商户号 |
| `YUSEN_KEY` | 是 | 无 | **是** | 悦森商户密钥（MD5 签名和查单都要用） |
| `YUSEN_GATEWAY_URL` | 是 | 无 | 否 | **由人工与悦森书面确认后填写**，例如 `https://<悦森网关域名>`，代码中没有硬编码 |
| `YUSEN_NOTIFY_URL` | 是 | 无 | 否 | `https://<联调公网域名>/api/v1/payment/notify/yusen` |
| `YUSEN_RETURN_URL` | 是 | 无 | 否 | `https://<联调公网域名>/api/v1/payment/return/yusen` |
| `PAYMENT_NETWORK_ENABLED` | 否 | **`false`** | 否 | 是否允许真实外发查单。**只在获批的联调窗口内设为 `true`** |
| `PAYMENT_JOBS_ENABLED` | 否 | `true` | 否 | 补偿任务（补发、查单、过期、迟到巡检、回调重试） |
| `PAYMENT_REUSE_PENDING_ORDERS` | 否 | **`false`** | 否 | 待支付订单复用。等悦森确认允许重复提交同一个 `out_trade_no` 后再评估是否开启 |
| `PAYMENT_ALERT_WEBHOOK_URL` | 否 | 空 | **是**（URL 中通常带令牌） | 告警推送地址，必须是 https；为空则只写日志、不对外发送 |
| `TRIAL_HMAC_SECRET` | 是（设备试用功能需要） | 无 | **是** | 必须与开发和测试环境的取值**不同** |
| `WECHAT_APP_CALCULATOR_VAULT_APP_ID` / `_APP_SECRET` | 需要真实微信登录时 | 无 | Secret 是机密 | |

为了兼容旧配置，`YUSEN_GATEWAY_BASE_URL` 和 `YUSEN_NETWORK_ENABLED` 仍然可以使用，但新部署请使用上表中的名称。

**机密的管理方式**：

- 使用密钥管理系统，或者权限为 `0600`、属主为服务账号的 EnvironmentFile；
- **不要**提交到 Git，不要写进 `.env.example`，不要在聊天、工单或文档中粘贴，不要在 shell 历史里出现；
- 应用日志不会输出商户密钥、签名链接或带密钥的查单 URL：日志对象会隐藏这些值，传输层的异常也不会带上 URL。

---

## 2. 独立数据库要求

1. 建一个专用 schema：`aurora_app_paystaging`，字符集 `utf8mb4` / `utf8mb4_unicode_ci`，时区 UTC。
2. 建一个专用账号，**只授予这个 schema 的权限**。不要复用开发库 `aurora_app` 或测试库 `aurora_app_test` 的账号。
3. 建议使用独立的 MySQL 实例，不要和本机开发容器（3308）、测试容器（3307）放在一起。
4. **数据库和账号都由人工创建**，应用不会自动建库。
5. 在执行 Flyway 迁移之前先做一次备份（即使是空库，也要记录基线）。

---

## 3. 端口规划

| 端口 | 用途 |
|---|---|
| 8080 | 本地开发环境（local profile），**保留** |
| 8081 | s32it 隔离测试环境（Fake 支付），**保留** |
| **8082** | paystaging 应用，**只在内网或本机监听** |
| 443 | 反向代理对外提供 HTTPS |

---

## 4. 公网 HTTPS 与反向代理要求

- 需要一个公网域名，以及**有效的**证书（例如 Let's Encrypt 或商用证书），并配置好自动续期。
- 反向代理只把需要的路径转发到 `127.0.0.1:8082`：
  - `/api/v1/**`（包括 `/api/v1/payment/notify/yusen` 和 `/return/yusen`）；
  - `/actuator/health`（建议只允许内网或运维 IP 访问）。
- 建议开启 HSTS，并转发 `X-Forwarded-Proto` 和 `X-Forwarded-For`。
- **访问日志**：
  - 回调请求的查询串里带有 `sign`，它不是密钥，可以记录；
  - 不要记录 `Authorization` 请求头；
  - 如果需要，可以给回调路径单独设置一个不带查询串的日志格式。
- 示例（Nginx 片段，仅作示意）：

```nginx
server {
    listen 443 ssl http2;
    server_name <联调公网域名>;
    ssl_certificate     <证书路径>;
    ssl_certificate_key <私钥路径>;
    add_header Strict-Transport-Security "max-age=31536000" always;

    location /api/v1/ {
        proxy_pass http://127.0.0.1:8082;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
    }
    location = /actuator/health {
        allow <运维IP>; deny all;
        proxy_pass http://127.0.0.1:8082;
    }
}
```

---

## 5. notify_url 与 return_url

| 地址 | 值 | 说明 |
|---|---|---|
| `YUSEN_NOTIFY_URL` | `https://<域名>/api/v1/payment/notify/yusen` | 悦森服务器用 GET 发起异步通知。服务端先验签和校验商户号，**再强制查单确认**，然后才发放会员；验签失败时应答 `fail`，其他情况应答 `success` |
| `YUSEN_RETURN_URL` | `https://<域名>/api/v1/payment/return/yusen` | 用户浏览器支付完成后跳转的页面，只显示"请返回 App 查看结果"，**不确认支付、不发放 VIP、不回显任何参数** |

**外网可达性检查**（部署后从公网执行，不会产生副作用）：

```bash
curl -s -o /dev/null -w "%{http_code}\n" "https://<域名>/api/v1/payment/return/yusen"   # 期望 200
curl -s "https://<域名>/api/v1/payment/notify/yusen"                                      # 期望返回 fail（因为没有签名）
```

注意：第二条命令会在数据库中留下一条 `REJECTED_*` 回调记录，并产生一条 `NOTIFY_REJECTED` 告警，这是预期行为。

另外，请向悦森索取回调的**来源 IP**。拿到后可以在反向代理上对 `notify` 路径设置 IP 白名单（可选，因为服务端已经要求验签并强制查单）。

---

## 6. 网关出口代理与 URL 日志泄露检查

悦森查单接口（`api.php?act=order`）按文档规定，要把**商户密钥以明文放在 GET URL 里**。所以必须确认没有任何中间环节会记录完整 URL：

1. 应用使用 JDK 自带的 HttpClient，**不读取 `HTTP_PROXY` / `HTTPS_PROXY` 环境变量**，只认 JVM 系统属性 `https.proxyHost` 等。检查启动参数里没有设置代理；如果必须经过代理，要确认代理**不记录**完整 URL，也不做 TLS 解密。
2. 不要开启 `-Djdk.httpclient.HttpClient.log=...`，也不要把 `jdk.internal.httpclient` 的日志级别调到 DEBUG。
3. 服务器出口防火墙只放行悦森网关域名的 443 端口。
4. 联调开始前在服务器上执行一次检查：在应用日志、代理日志和防火墙日志中搜索 `act=order`、`key=`，结果应该为 0。
5. 需要向悦森确认：查单是否支持 POST（支持的话，后续可以把密钥从 URL 中移走）。

---

## 7. Flyway 迁移前的检查步骤

1. 确认 `SPRING_PROFILES_ACTIVE=paystaging`，并且**没有**其他 profile。
2. 用应用账号手动连接一次，执行 `SELECT DATABASE();`，结果必须是 `aurora_app_paystaging`。
3. 确认目标库是空库，或者 `flyway_schema_history` 中的版本不高于 V5。
4. 做好备份并记录备份位置。
5. 启动应用。应用会自动依次执行：配置层校验，迁移前对实际连接库的校验，Flyway 的 V1 到 V5，以及启动完成时的二次校验。
6. 启动后核对：`flyway_schema_history` 显示 V1–V5 全部成功；`membership_plans` 中只有月卡 590 分 / 30 天、年卡 1990 分 / 365 天、永久会员 2990 分这三条。

---

## 8. 启动、停止与健康检查

```bash
# 构建生产 jar（不含 Fake 和测试夹具；构建时会自动检查）
./gradlew check bootJar

# 启动（环境变量从受保护的 EnvironmentFile 或密钥管理系统注入）
java -jar build/libs/aurora-app-server-<version>.jar
#   SPRING_PROFILES_ACTIVE=paystaging，其余变量见第 1 节

# 健康检查
curl -s http://127.0.0.1:8082/actuator/health        # 期望 {"status":"UP"}

# 停止（systemd 示例）
systemctl stop aurora-paystaging
```

systemd 单元的要点：

- 使用专用的系统用户运行；
- 用 `EnvironmentFile=/etc/aurora/paystaging.env` 注入环境变量（文件权限 0600）；
- 设置 `Restart=on-failure`；
- 应用日志写到受限目录。

启动失败时，日志中会有一行 `Refusing to start profile 'paystaging': <原因>`，原因说明只包含变量名，不包含变量的值。

**真实联调窗口的开启和关闭**：经人工书面批准后，把 `PAYMENT_NETWORK_ENABLED` 设为 `true` 并重启；联调结束后改回 `false` 再重启。

---

## 9. 回滚方案

1. **立即止损**：把 `PAYMENT_NETWORK_ENABLED` 设为 `false` 并重启，停止真实外发；或者直接停止服务。
2. **应用回滚**：重新部署上一个版本的 jar，环境变量不变。
3. **数据库回滚**：Flyway 没有向下迁移，需要用第 7 节第 4 步的备份恢复。**不要手工删除支付相关的数据行**（订单、回调、外呼流水、事件、会员发放）。如果有付款发生在回滚窗口内，要先导出这些数据，人工核对后再决定怎么处理。
4. **服务停止期间的影响**：悦森的回调会失败。重试策略待悦森确认。服务恢复后，补偿任务会通过查单补齐：待支付订单、24 小时内的迟到巡检、7 天内的回调重试。
5. 回滚后，用 `anomaly_code` 不为空的订单和 `PAYMENT_ALERT` 日志核对是否有遗漏。

---

## 10. 正式联调前的人工确认清单

- [ ] 目标分发渠道已经确定为小米、OPPO、vivo、应用宝等国内市场，相关合规材料准备完毕（会员协议、价格和权益说明、退款规则、App 备案）。
- [ ] 悦森已经书面确认：正式网关域名、已开通的收款通道、`out_trade_no` 限制、同一订单号能否重复提交、回调重试策略和来源 IP、查单 `code≠1` 的含义、是否支持 POST、币种、`endtime` 时区、收银台页面有效期。
- [ ] 公网 HTTPS 域名和有效证书已经就绪；第 5 节的可达性检查已通过。
- [ ] 独立数据库 `aurora_app_paystaging` 和专用账号已经建好，迁移前备份已完成。
- [ ] 全部环境变量已通过受保护的方式注入；第 6 节的出口和 URL 日志检查已通过。
- [ ] 告警 Webhook 已配置，或者明确由人工盯 `PAYMENT_ALERT` 日志，并指定了值守人员。
- [ ] Android 联调构建已就绪：HTTPS 地址、悦森域名白名单、禁止 TEST 类型、已签名可安装（属于 I3.5.5-B2）。
- [ ] 真实小额支付的预算、批准人和资金处理方式（退款通过悦森商户后台手动操作）已经书面确认。
- [ ] 开启 `PAYMENT_NETWORK_ENABLED=true` 的时间窗口已获批准。
