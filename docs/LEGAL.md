# 法律与合规说明（Legal & Compliance Notes）

> 文档性质：面向开源发布的风险自查与整改记录，**不构成法律意见**。涉及商业发行、并购、合规认证前，请咨询执业律师。
> 适用版本：`0.9.0`（2026-11-08）｜项目：九鼎 · 多租户智能中枢（Jiuding）

## 1. 许可结构

| 层 | 许可 | 说明 |
|---|---|---|
| 自研代码/文档 | **Apache-2.0** | 根目录 `LICENSE`；587 个源文件含 SPDX 风格头 + `@author suhong` |
| 第三方依赖（运行时） | 各自许可 | 见 `THIRD_PARTY_LICENSES.md` 与 `LICENSES/` |
| 第三方基础镜像 | 各自许可 | Temurin（GPLv2+CE）、PostgreSQL License、nginx BSD-2、curl License |
| 前端产物 | 含 MIT/ISC 依赖 | 分发 `frontend/dist` 时需保留相应许可证声明 |

**Apache-2.0 义务**：保留版权/许可/NOTICE；修改文件需显著标注；专利授权与终止条款自动适用；不提供商标授权。

## 2. 原创性与版权

- 仓库内 Java/TypeScript/SQL/脚本均由本项目编写（AI 辅助），文件头声明 `Copyright 2026 suhong`。
- `agent-kernel` 是 **AgentScope Java 公共 API 的独立封装**（ReActAgent/Toolkit/Model 适配），未复制其源码；AgentScope 采用 Apache-2.0，见 `NOTICE` 归属说明。
- 未有意引入第三方源码片段、StackOverflow 代码、商业字体/图标/图片；UI 截图为本项目运行截图。
- 若权利人认为存在未署名内容，请通过 `SECURITY.md`/Issue 联系，核实后会在下一版本更正或移除（Apache-2.0 §4 允许并鼓励准确署名）。

**风险等级：低**。剩余风险：AI 辅助生成代码可能与公开代码片段相似，建议商业发行前做一次版权扫描（如 FOSSA/ScanCode）。

## 3. 商标与名称

| 名称 | 使用方式 | 风险与整改 |
|---|---|---|
| **九鼎 / Jiuding** | 项目代号/产品名 | “九鼎”为中文常用词，亦有多家同名企业/商标。本项目**不主张商标权**，与任何同名机构无关联；如用于商业产品，建议另行商标检索与注册 |
| **Codex-inspired** | 仅描述视觉风格 | 已在文档/README 加“仅风格参考、与 OpenAI 无关联/背书”声明；不再作为产品名使用 |
| AgentScope、Spring、React、PostgreSQL、nginx 等 | 指明依赖/兼容对象（nominative fair use） | 保留其名称与许可证归属，不暗示官方背书 |
| 截图中的品牌 | 本项目自有 UI | 无第三方 Logo |

整改：`README`/`NOTICE`/`docs/12` 已加入非关联声明；`docs/12-codex-ui-spec.md` 已更名为 `docs/12-ui-design-spec.md`。

## 4. 专利

- Apache-2.0 含明示专利授权（贡献者专利许可）及专利诉讼终止条款。
- 未发现本项目使用需另行许可的专有算法；规则引擎/Agent 编排/多租户模型为通用工程实现。
- 使用的密码学原语（HMAC-SHA256、PBKDF2、TOTP）为公开标准算法，不涉及专利许可费（常规实现）。
- **风险等级：低**。商业发行前建议做 FTO（自由实施）检索。

## 5. 出口管制与密码学

- 项目包含标准密码学调用（JWT HS256、PBKDF2、TOTP、TLS 由部署层提供），未实现自有加密算法或高强度加密产品。
- 多数司法辖区对“大众市场软件中的标准加密”不视为受控；但各国规则不同。
- **建议**：如向受制裁地区/主体分发，或嵌入硬件产品，请自行做出口管制分类（如美国 EAR/ECCN）。

## 6. 数据保护与隐私（PIPL/GDPR）

- 仓库内演示数据（`docs`、测试、示例账号）均为**虚构数据**（example.com、随机用户名、假手机号），不含真实个人信息。
- 部署本系统处理真实租户/员工数据时，你将成为**个人信息处理者**，需自行完成：合法性基础、告知同意、最小化、跨境评估（如适用）、数据主体权利响应、留存与删除策略。
- 本项目**不提供**数据处理协议（DPA）、等保/ISO 认证或合规审计报告。
- 默认密钥/演示账号仅用于本地，公网部署前必须替换（`SECURITY.md`）。

## 7. 安全与责任限制

- 软件按 Apache-2.0 “AS IS” 提供，无任何明示/默示担保；作者不对数据丢失、业务中断、合规处罚承担责任。
- 当前为 **0.9.0 预发布、非生产级**；已知限制见 `SECURITY.md` 与 `PRODUCT_GUIDE.md`。
- 漏洞请私密报告，勿公开披露。

## 8. 二进制/Docker 分发义务（重要）

若你构建并分发 Docker 镜像或二进制包，必须随附：
1. `LICENSE`、`NOTICE`、`THIRD_PARTY_LICENSES.md`、`LICENSES/` 目录；
2. H2（MPL-2.0）许可文本与源码获取方式（H2 未修改，提供官方源码链接即可）；
3. Temurin JRE（GPLv2+CE）源码获取方式（见 `LICENSES/Temurin-GPLv2-CPE.md`）；
4. nginx（BSD-2）、PostgreSQL（PostgreSQL License）、curl（curl License）、React/Vite 等 MIT 组件的版权与许可声明；
5. 不得移除第三方组件内的版权/许可文件。

## 9. AI 辅助生成内容

本项目部分代码、测试与文档由 AI 辅助生成并经人工审查。作者不对“完全无第三方相似片段”作绝对保证；如发现侵权内容，将按通知移除或替换。商业使用前建议进行代码来源扫描与人工复核。

## 10. 整改记录（本次）

- [x] 根目录 `LICENSE`（Apache-2.0 全文）
- [x] `NOTICE`：版权、第三方归属、商标/非关联声明、演示数据声明
- [x] `THIRD_PARTY_LICENSES.md`：逐组件许可证、分发义务、完整依赖查询方法
- [x] `LICENSES/`：Apache-2.0、MPL-2.0、MIT、BSD-2、PostgreSQL、EPL-1.0、Temurin GPLv2+CE 说明
- [x] 587 个源文件 Apache-2.0 头 + `@author suhong`（CI 校验）
- [x] “Codex 风格”改为“Codex-inspired 视觉参考”，并加非关联声明；设计文档更名
- [x] README 增加 Legal/Trademarks/Demo data 声明并链接本文档
- [x] 仓库无 `.env`、私钥、kubeconfig 等敏感文件；`deploy/k8s/01-secrets.yaml` 标注 DEMO ONLY
- [x] K8s 密钥改为 `gen-secrets.sh` 随机生成；`01-secrets.yaml` 仅保留 `CHANGE_ME_*` 模板
- [x] `scripts/scan-secrets.ps1`（工作区 + Git 历史高置信度密钥扫描）已接入 CI；本地扫描 PASS
- [x] 演示默认凭据清单与轮换步骤写入 `SECURITY.md`
- [x] 前端 `package.json` 声明 `Apache-2.0`；Maven `pom` 版本/坐标统一
- [x] 依赖扫描：npm 锁文件无 GPL/AGPL/SSPL；Java 依赖均为 Apache-2.0/BSD/MIT/MPL/EPL 类

## 11. 商业使用前建议（非强制）

1. 律师复核 Apache-2.0 合规与商标使用；
2. 商标检索/注册（若以“九鼎/Jiuding”商业化）；
3. FOSSA/ScanCode 全量依赖与代码相似度扫描；
4. 数据保护影响评估（DPIA）与 DPA 模板；
5. 出口管制分类与开源治理制度（OSPO）。
