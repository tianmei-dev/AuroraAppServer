# AuroraAppServer

轻量级多 Android App 共用业务服务端（单体架构）。

- **首个接入应用**：隐藏计算器（`calculator_vault` / `com.aurora.calculatorvault`）
- **基础包名**：`com.aurora.appserver`
- **数据库**：`aurora_app`（MySQL 8.x + Flyway）
- **当前阶段**：S1.1 — 多应用基础工程（不含登录 / 试用 / 会员 / 支付）

## 技术栈

| 组件 | 版本 |
|------|------|
| Java | 21 |
| Spring Boot | 3.5.x |
| Gradle | Wrapper |
| MySQL | 8.x |
| Flyway | 随 Boot 管理 |
| Docker Compose | 开发库（3308）+ 隔离测试库（3307） |

## 快速开始（Windows + PowerShell）

### 1. 前置条件

- JDK 21
- Docker Desktop（本地开发库 + 集成测试库）

端口约定：

| 用途 | Compose 文件 | 本机端口 | 库名 | Volume |
|------|--------------|----------|------|--------|
| 本地开发 / 人工验收 | `docker-compose.dev.yml` | **3308** | `aurora_app` | `aurora_mysql_dev_data` |
| 自动化集成测试 | `docker-compose.test.yml` | 3307 | `aurora_app_test` | （无持久化） |

### 2. 启动开发数据库（Docker Desktop）

```powershell
cd D:\Android_Work_Server\AuroraAppServer
Copy-Item .env.example .env
# 编辑 .env，设置 MYSQL_PASSWORD / MYSQL_ROOT_PASSWORD

docker compose -f docker-compose.dev.yml up -d
docker compose -f docker-compose.dev.yml ps
# 等待 healthy 后继续
```

### 3. 本地 Server 配置

```powershell
Copy-Item src\main\resources\application-local.yml.example src\main\resources\application-local.yml
```

将 `application-local.yml` 中的 `password` 改成与 `.env` 里 `MYSQL_PASSWORD` 相同（两文件均已 `.gitignore`）。

### 4. 启动 Server 与健康检查

首次使用建议把 Gradle 用户目录放到 D 盘（避免 C 盘空间不足 / 外网下载超时）。本机离线包与缓存已约定：

- 离线 zip：`D:\Android_Work_Server\tools\gradle-8.14.3-bin.zip`
- 缓存目录：`D:\Android_Work_Server\.gradle-home`（勿删；已预置 8.14.3）

**VS Code / 终端推荐（不再访问 services.gradle.org）：**

```powershell
# 新开终端后任选其一
.\bootRun.bat

# 或（需已设置 GRADLE_USER_HOME，见下方）
.\gradlew.bat bootRun
```

若直接跑 `.\gradlew.bat` 仍去下载，先执行：

```powershell
$env:GRADLE_USER_HOME = "D:\Android_Work_Server\.gradle-home"
$env:TEMP = "D:\Android_Work_Server\.tmp"
$env:TMP = $env:TEMP
```

项目已提供 `.vscode/settings.json`，在 VS Code 中**新开终端**会自动带上上述环境变量。

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

> 若外网下载 Gradle 失败，可将本机已有包放到  
> `D:\Android_Work_Server\tools\gradle-8.14.3-bin.zip`，  
> 并把 `gradle/wrapper/gradle-wrapper.properties` 中的 `distributionUrl` 临时改为：  
> `file:///D:/Android_Work_Server/tools/gradle-8.14.3-bin.zip`（勿把该本机路径提交到 Git）。

- 健康检查：`GET http://localhost:8080/actuator/health`
- 业务 API 前缀：`/api/v1`（S1.1 默认全部需认证，暂无公开业务接口）

### 5. DBeaver 连接开发库

| 项 | 值 |
|----|-----|
| Host | `127.0.0.1` |
| Port | `3308` |
| Database | `aurora_app` |
| Username | `.env` 中的 `MYSQL_USER`（默认 `aurora`） |
| Password | `.env` 中的 `MYSQL_PASSWORD` |

建议：Driver 属性 `serverTimezone=UTC`，连接字符集 utf8mb4。验收时查看表 `apps`、`flyway_schema_history`。

### 6. 测试

先启动隔离的测试库（端口 3307，库名 `aurora_app_test`，**不会**修改开发库 `aurora_app`）：

```powershell
docker compose -f docker-compose.test.yml up -d
.\gradlew.bat test
```

可通过环境变量覆盖测试库连接：`TEST_DB_HOST` / `TEST_DB_PORT` / `TEST_DB_NAME` / `TEST_DB_USERNAME` / `TEST_DB_PASSWORD`。

## 工程结构（摘要）

```
src/main/java/com/aurora/appserver/
  common/     统一响应、错误码、全局异常处理
  app/        应用注册表、AppRegistryService、AppContext
  security/   Spring Security 默认拒绝策略
```

预留模块包（S1.1 不实现）：`auth` / `trial` / `membership` / `payment`。

## 文档

| 文档 | 说明 |
|------|------|
| [docs/architecture.md](docs/architecture.md) | 架构总览 |
| [docs/app-isolation.md](docs/app-isolation.md) | 多 App 隔离原则 |
| [docs/data-model.md](docs/data-model.md) | 数据模型 |
| [docs/api-conventions.md](docs/api-conventions.md) | API 约定 |
| [docs/S1.1-acceptance.md](docs/S1.1-acceptance.md) | S1.1 验收清单 |

## 安全提示

- 不要提交 `application-local.yml`、`.env` 或真实密码。
- `X-App-Code` 仅用于应用路由与配置查找，**不能**证明请求来自对应 Android App。
- Actuator 仅暴露 `health`，且不返回敏感细节。
