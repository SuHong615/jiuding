# Kubernetes 部署（本地 k3s，已实测）

本目录提供多租户中心的 Kubernetes 清单，已在 **WSL2 内的单节点 k3s v1.30.6** 上完成真实部署与 E2E 验证。

## 实测结果（2026-11-08）

| 项 | 结果 |
|---|---|
| 集群 | k3s v1.30.6+k3s1，节点 `Ready` |
| 部署 | 7 个 Pod 全部 `Running`（postgres + tenant/approval/agent/mcp/gateway + frontend） |
| 就绪 | 6 个 Deployment rollout 全部完成，就绪探针通过 |
| E2E（集群端口） | `integration/verify.ps1` **16/16 PASS**（新库 tenants=1，证明确实命中集群） |
| 团队验证 | `scripts/verify.ps1 -BaseUrl http://localhost:30080` **10/10 PASS**（E2E + MCP） |
| 数据 | `tenant_db.tenants=1`、`approval_db.approvals=1 (ACTIVE)`、`agent_db.skill_registry=4` |
| 前端 | `http://localhost:30088/` 200；`POST /api/auth/login` 经 nginx 反代成功 |

## 组成

| 资源 | 说明 |
|---|---|
| Namespace `tenancy-center` | 隔离整个平台 |
| Secret `tenancy-secrets` | PG 凭据、JWT secret、内部 token、MCP API Key |
| PVC `postgres-data`（2Gi） | Postgres 数据（k3s 默认 local-path） |
| Deployment/Service `postgres` | PostgreSQL 16，init 创建 tenant_db/approval_db/agent_db |
| Deployments | tenant 8081、approval 8082、agent 8083、mcp 8084、gateway 8080、frontend 80 |
| Services | 全部 NodePort，见下表 |
| 镜像 | 由 docker 构建后 `docker save \| k3s ctr images import -` 导入，无需镜像仓库 |

## NodePort / 访问矩阵

| 服务 | NodePort | Windows 访问（publish 后） |
|---|---|---|
| gateway | 30080 | `http://localhost:30080`（API + `/mcp`） |
| tenant-service | 30081 | `http://localhost:30081` |
| approval-service | 30082 | `http://localhost:30082` |
| agent-service | 30083 | `http://localhost:30083` |
| mcp-service | 30084 | `http://localhost:30084` |
| frontend | 30088 | `http://localhost:30088` |

> **为什么需要发布脚本**：本机 WSL2 只把 **docker publish 的端口**自动转发到 Windows；k8s NodePort / kubectl port-forward 是 WSL 内原始监听，Windows `localhost` 访问不到。`publish.sh` 用 `alpine/socat` 容器把 NodePort 发布到 docker 网桥（容器发布端口 → Windows 可见），从而 `localhost:300xx` 可达。

## 前置

1. k3s Ready：`wsl -d opencode-docker -e /bin/sh -c 'kubectl get nodes'`（kubeconfig `/etc/rancher/k3s/k3s.yaml`）
2. 镜像已存在（二选一）：
   - 生产：`cd deploy && docker compose build`（多阶段 Dockerfile）
   - 快速：`deploy/k8s/runtime.Dockerfile` + 已构建 jar/dist（见 `deploy.sh` 注释）

## 部署 / 停止

```sh
# WSL 内执行（仓库通过 /mnt/d 可见）
sh /mnt/d/deepseekHarness/tenancy-center/deploy/k8s/deploy.sh          # 导入镜像 + apply + 等待 rollout
sh /mnt/d/deepseekHarness/tenancy-center/deploy/k8s/deploy.sh status   # 查看
sh /mnt/d/deepseekHarness/tenancy-center/deploy/k8s/deploy.sh delete   # 删除 namespace

# Windows 访问（socat 发布 NodePort）
wsl -d opencode-docker -e /bin/sh /mnt/d/deepseekHarness/tenancy-center/deploy/k8s/publish.sh start
wsl -d opencode-docker -e /bin/sh /mnt/d/deepseekHarness/tenancy-center/deploy/k8s/publish.sh stop
```

## 运维

```sh
kubectl -n tenancy-center get pods -o wide
kubectl -n tenancy-center logs -f deploy/gateway
kubectl -n tenancy-center describe pod -l app=agent-service
kubectl -n tenancy-center exec deploy/postgres -- psql -U tenancy -d tenant_db -c 'select tenant_code,status from tenants'
```

## 生产化 overlay（v4，已实测）

```sh
sh /mnt/d/deepseekHarness/tenancy-center/deploy/k8s/prod/apply-prod.sh
sh /mnt/d/deepseekHarness/tenancy-center/deploy/k8s/zero-downtime-check.sh 90
```

| 项 | 结果 |
|---|---|
| 副本 | 6 个无状态服务 2/2 Running |
| PDB | 6 个 `minAvailable=1` |
| HPA | gateway / tenant-service，CPU 70%，min1/max3 |
| CronJob | `rating-refresh` 每日 19:00 UTC（03:00 CST）调用 `/internal/ratings/refresh`，手动 Job `refreshed=14, skipped=4, failed=0` |
| 零中断 | 滚动重启期间 failures=0 |

> `timeZone` 字段在最小化 k3s apiserver 上可能因缺 tzdata 报错，清单使用 UTC 时间表达。

## 密钥生成（必须）

`deploy.sh` 与 `prod/apply-prod.sh` 会自动调用 `gen-secrets.sh`：

- Secret `tenancy-secrets` 不存在时，生成随机 JWT secret、内部令牌、PostgreSQL/mcp_db 密码与 MCP API Key；
- Secret 已存在时**不覆盖**（避免破坏已初始化的 PostgreSQL 数据目录）；
- `01-secrets.yaml` 只是 `CHANGE_ME_*` 模板，**不要直接 apply**（脚本不会应用它）。

本地验证需要 MCP API Key：
```sh
set -a; . deploy/k8s/.generated-env; set +a
pwsh scripts/verify.ps1 -BaseUrl http://localhost:30080 -McpApiKey "$MCP_API_KEY"
```

轮换：`kubectl -n tenancy-center delete secret tenancy-secrets` 后重跑 `gen-secrets.sh`，并同步修改 PostgreSQL 用户口令。

## 备份与恢复（PostgreSQL，四库）

数据位于 `postgres` Deployment 的 PVC，包含 `tenant_db` / `approval_db` / `agent_db` / `mcp_db`。
`backup.sh` / `restore.sh` 通过 `kubectl exec` 在 pod 内执行 `pg_dump -Fc` / `pg_restore`，无需本地 psql 客户端。

```sh
# 备份（WSL 内执行）；默认 deploy/k8s/backup/YYYYmmdd-HHMMSS，保留最近 7 份
sh deploy/k8s/backup.sh
sh deploy/k8s/backup.sh --out /data/jiuding-backups --keep 14

# 恢复：必须显式 --yes，否则脚本拒绝执行（exit 2）
sh deploy/k8s/restore.sh --from deploy/k8s/backup/20261108-190945 --yes
sh deploy/k8s/restore.sh --from <dir> --db tenant_db,mcp_db --yes
```

产物结构：

```
<out>/YYYYmmdd-HHMMSS/
├── tenant_db.dump  approval_db.dump  agent_db.dump  mcp_db.dump
└── manifest.txt    # time/namespace/image/version + 每库 file/size/sha256
```

- `restore.sh` 会先按 manifest 校验 sha256，再执行 `pg_restore --clean --if-exists --no-owner`；恢复会**删除并重建**目标库对象。
- 建议：每日 02:00 全量 + `--keep 7`；发布/迁移前手工备份；备份目录定期同步到 S3/MinIO；每月做一次恢复演练（独立 namespace）。
- 实测（2026-11-08 k3s）：`backup.sh --out /tmp/jiuding-backup --keep 3` 四库 dump + manifest 成功，sha256 与 manifest 一致；仅验证备份，未执行恢复。

## 说明与限制

- 面向**本地单节点验证**：单副本、local-path 存储、`IfNotPresent` + 本地导入镜像，无 HPA/PDB/NetworkPolicy。
- 生产化建议：外部镜像仓库、Secret 管理（SealedSecrets/External Secrets）、多副本 + 反亲和、Ingress + TLS、ResourceQuota/NetworkPolicy、Postgres 外部化。
- 内存预算：namespace 实测约 2.4 GiB（JVM `MaxRAMPercentage=45`、PG `shared_buffers=128MB`），可与本机其它容器共存。
- `portforward.sh` 为 WSL 内备选（Windows 无法直连 WSL 原始监听）。
