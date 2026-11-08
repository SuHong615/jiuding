# 04 · Skill 规范（技能包 + 规则 DSL）

Skill 是本平台"可插拔的租户业务知识单元"，同时服务三类消费者：
1. **Agent**：作为 System Prompt 片段 + 工具集的组合注入 AgentScope ReActAgent；
2. **规则引擎**：`rules.yaml` 由纯 Java 确定性执行（不依赖 LLM，可测试、可审计）；
3. **MCP/CLI/前端**：通过 `/api/skills` 与 MCP `skill.run` 暴露。

## 目录结构

```
skills/<skill-id>/
├── SKILL.md        # 必须：YAML frontmatter + Markdown 指令（Agent 注入）
├── rules.yaml      # 可选：确定性规则
└── examples/       # 可选：样例输入输出（测试夹具）
```

## SKILL.md 格式

```markdown
---
id: risk-review
name: 租户风险审查
version: 1.0.0
type: RISK                 # INTAKE | RISK | APPROVAL | PROVISIONING | RATING
description: 根据租户行业、区域、配额与联系人信息计算风险分并给出审批建议
triggers: [tenant.submitted, approval.risk_check]
tools: [tenant.get, approval.list_pending]
tags: [risk, compliance]
---
# 角色
你是租户风险审查 Agent……

# 工作流
1. 调用 tenant.get 获取租户画像
2. 依据规则命中情况给出 riskLevel / decision
3. 输出 JSON：{"riskLevel":"...","score":0,"decision":"...","reasons":[]}
```

frontmatter 必填：`id/name/version/type/description`；`triggers/tools/tags` 可选。

## rules.yaml DSL

```yaml
skillId: risk-review
version: 1.0.0
initialScore: 0
rules:
  - id: R-RISK-001
    description: 高风险行业 + 大配额
    severity: HIGH
    when:
      all:
        - field: attributes.industry
          op: in
          value: [gambling, crypto-exchange]
        - field: quotaSeats
          op: gt
          value: 100
    then:
      - action: addScore
        value: 45
      - action: addFinding
        message: 行业与配额组合触发高风险
  - id: R-RISK-010
    when: { field: contactEmail, op: matches, value: "^[^@\\s]+@[^@\\s]+\\.[a-z]{2,}$" }
    then: [ { action: addScore, value: -5 } ]
decision:
  autoApproveMaxScore: 20
  rejectMinScore: 80
  riskLevels:
    - { level: LOW, maxScore: 20 }
    - { level: MEDIUM, maxScore: 60 }
    - { level: HIGH, maxScore: 100 }
```

**条件**
- 组合：`all: [...]`、`any: [...]`、`not: {...}`，可嵌套
- 叶子：`{ field, op, value }`；`field` 支持点路径 `attributes.industry`
- 运算符：`eq ne gt gte lt lte in notIn contains matches exists empty`
- 类型比较：数字按数值、布尔按布尔、其它按字符串；`exists` 只判断非 null/非空

**动作**
| action | 参数 | 语义 |
|---|---|---|
| `addScore` | `value` | 累加评分 |
| `addFinding` | `message`, `severity` | 记录发现（HIGH/MEDIUM/LOW） |
| `setDecision` | `value` | 覆盖决策（AUTO_APPROVE/NEEDS_REVIEW/REJECT） |
| `setRiskLevel` | `value` | 覆盖风险等级 |
| `requireRole` | `value` | 审批节点所需角色 |
| `setSlaHours` | `value` | 审批 SLA 小时数 |
| `addTag` | `value` | 打标签 |

**决策**：最终 `score` 映射 `riskLevels`；`score <= autoApproveMaxScore` → `AUTO_APPROVE`，`>= rejectMinScore` → `REJECT`，否则 `NEEDS_REVIEW`。
显式 `setDecision` 优先级最高。

## 内置技能包（必须交付）

| skill-id | type | 职责 |
|---|---|---|
| `tenant-intake` | INTAKE | 必填校验、邮箱/域名规范、字段归一化（region/plan 别名）、重复租户识别 |
| `risk-review` | RISK | 行业/区域/配额/联系人风险评分 → riskLevel + decision |
| `approval-policy` | APPROVAL | 由 riskLevel+plan 决定审批路径（自动通过 / L1 / L2 会签）与 SLA |
| `provisioning` | PROVISIONING | 由 plan+isolationMode 生成资源计划（命名空间、配额、isolated schema 名） |
| `tenant-rating` | RATING | 五维加权评级（业务/合规/财务/安全/服务）→ 调整分 + 发现，支撑 AAA~D 租户评级（docs/11） |

每个技能包需附 `examples/`（至少 1 组输入/期望输出），供 agent-service 单测引用。

## 安全与执行约束

- Skill 只读加载自 `skills/`（env `TENANCY_SKILLS_DIR` 可覆盖），启动与 `/api/skills/reload` 时热加载；
- 规则执行禁止反射/脚本注入；`matches` 使用受限正则（长度 ≤ 256）；
- 单次 `skill.run` 超过 2s 记 WARN；任何规则异常不得中断整体执行，记入 `trace` 且 `verdict=WARN`。
