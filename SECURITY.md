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

## 已知安全限制
- 共享 schema 多租户隔离依赖应用层，尚未启用数据库 RLS
- 登录限流为进程内内存实现，多副本不共享
- 默认启用演示账号（`admin/Admin@123` 等），生产必须移除或强制改密
- OIDC PKCE verifier 为网关进程内存储，多副本需共享存储
