# Integration harness (Lead-owned)

本目录是 Lead 用于"本地无 Docker 环境"的进程级集成验证台，独立于 `scripts/`（交付给用户的运维脚本）。

- `start.ps1`：构建并后台启动 5 个 Spring Boot 服务（dev/H2 profile），日志写入 `integration/.run/*.log`
- `stop.ps1`：停止由 start.ps1 启动的进程
- `verify.ps1`：端到端验收（登录→建租户→提交→审批→MCP→CLI），PASS/FAIL 汇总，失败非 0 退出

用法：
```powershell
pwsh integration/start.ps1
pwsh integration/verify.ps1
pwsh integration/stop.ps1
```
