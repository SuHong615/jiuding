# 安全策略（Security Policy）

## 支持范围
本项目为 **0.9.x 参考实现**，不承诺生产级安全支持；当前仅对 `main` 分支的最新代码做安全修复。

## 报告漏洞
请通过 GitHub Security Advisory（私密）或邮件联系维护者 **suhong**，勿公开披露。报告请包含：
- 影响版本 / 提交
- 复现步骤（PoC）
- 影响评估（数据泄露、越权、RCE 等）

我们会在 7 个工作日内确认，并在修复后致谢（如你同意）。

## 部署安全基线（必须）
1. 替换全部默认密钥：`TENANCY_SECURITY_JWT_SECRET`、`TENANCY_INTERNAL_TOKEN`、`TENANCY_SECURITY_API_KEYS`、`POSTGRES_PASSWORD`、`MCP_DB_PASSWORD`
2. 使用外部化数据库与密钥管理（Vault/KMS/Secret Manager），不要使用仓库内示例值
3. 启用 TLS（Ingress/网关终止）并限制管理端口暴露
4. 为 MCP/SCIM/OIDC 令牌设置轮换与最小权限
5. 生产部署前执行独立安全评审与渗透测试

## 默认演示凭据清单（必须替换/删除）

| 位置 | 默认值 | 说明 |
|---|---|---|
| `deploy/.env.example`、`docker-compose.yml` fallback | `tenancy-dev-pass` | PostgreSQL / mcp_db 密码 |
| 同上 | `tenancy-center-dev-secret-change-me-please` | JWT 签名密钥（≥16 字符） |
| 同上 | `dev-internal-token` | 服务间 `/internal/**` 令牌 |
| 同上 | `dev-mcp-key` | MCP 静态 `X-API-Key` |
| 演示账号（文档/测试） | `admin/Admin@123` 等 | 平台/审批/租户演示账号 |
| `deploy/k8s/01-secrets.yaml` | `CHANGE_ME_*` 占位符 | **不会被 deploy.sh 应用**；由 `gen-secrets.sh` 生成随机 Secret |

### 本地 Docker Compose
复制 `.env.example` 后，用随机值覆盖（示例见文件头）：
```sh
openssl rand -base64 32   # 数据库密码
openssl rand -base64 48   # JWT secret
openssl rand -base64 32   # internal token
```

### Kubernetes（k3s）
`deploy.sh` 与 `prod/apply-prod.sh` 会调用 `deploy/k8s/gen-secrets.sh`：
- Secret 不存在时，生成随机值并创建 `tenancy-secrets`；
- Secret 已存在时**不覆盖**（避免破坏已初始化的 PostgreSQL）；
- 生成的 MCP API Key 写入 `deploy/k8s/.generated-env`（gitignored），本地验证：
  ```sh
  set -a; . deploy/k8s/.generated-env; set +a
  pwsh scripts/verify.ps1 -BaseUrl http://localhost:30080 -McpApiKey "$MCP_API_KEY"
  ```
- 轮换：先 `kubectl delete secret tenancy-secrets` 再重跑 `gen-secrets.sh`，并同步修改 PostgreSQL 用户密码。

### 密钥扫描
```powershell
pwsh ./scripts/scan-secrets.ps1   # 扫描工作区 + Git 历史中的高置信度密钥模式；CI 已启用
```
当前仓库**未发现真实密钥/私钥/云令牌**；历史提交中仅存在上述演示默认值（非真实凭据）。

## 已知安全限制
- 共享 schema 多租户隔离依赖应用层，尚未启用数据库 RLS
- 登录限流为进程内内存实现，多副本不共享
- 默认启用演示账号（`admin/Admin@123` 等），生产必须移除或强制改密
- OIDC PKCE verifier 为网关进程内存储，多副本需共享存储
