# API 约定

## 前缀

所有业务 API 使用：

```text
/api/v1
```

S1.1 默认 **不开放** 任何业务接口；`/api/v1/**` 需认证。

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

S1.1 已定义：

| code | 常量 | 含义 |
|------|------|------|
| 0 | `SUCCESS` | 成功 |
| 40001 | `APP_NOT_FOUND` | 应用不存在 |
| 40002 | `APP_DISABLED` | 应用已停用 |
| 40003 | `APP_CONTEXT_REQUIRED` | 缺少应用上下文 |
| 40004 | `VALIDATION_FAILED` | 参数校验失败 |
| 40100 | `UNAUTHORIZED` | 未认证 |
| 40300 | `FORBIDDEN` | 无权限 |
| 40400 | `NOT_FOUND` | 路由/资源不存在 |
| 50000 | `INTERNAL_ERROR` | 内部错误（不返回堆栈） |

## 应用标识请求头（预留）

```http
X-App-Code: calculator_vault
```

注意：

- 仅用于路由与配置查找。
- **不能**证明请求真实来自该 Android App。
- 服务端不得直接信任客户端传入的数字 `app_id`。

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
