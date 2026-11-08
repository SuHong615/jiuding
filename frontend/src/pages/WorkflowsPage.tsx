import { api } from '../api/client'
import type { WorkflowLifecycleView, WorkflowStageView } from '../api/types'
import { useAsync } from '../hooks/useAsync'
import { Badge } from '../components/Badge'
import { SectionCard } from '../components/Forms'
import { Icon } from '../components/Icon'
import { formatDateTime } from '../utils/format'

const STATIC_STAGES: WorkflowStageView[] = [
  {
    stage: 'DRAFT',
    label: '草稿',
    description: '租户资料录入与准入校验，尚未进入审批。',
    activities: ['创建租户 / 自助入驻', '填写联系人与配额', 'tenant-intake 准入校验'],
    skills: ['tenant-intake'],
    agents: ['tenant-intake'],
    outputs: ['DRAFT 租户', '准入校验结果'],
  },
  {
    stage: 'SUBMITTED',
    label: '已提交',
    description: '租户提交审批，审批单创建并进入风险审查。',
    activities: ['提交租户生命周期', 'approval-service 建单'],
    skills: [],
    agents: [],
    outputs: ['审批单 RISK_CHECK'],
  },
  {
    stage: 'RISK_REVIEW',
    label: '风险审查',
    description: '规则引擎与 Agent 对行业、区域、配额、联系人等进行评分。',
    activities: ['risk-review 规则执行', '写入风险等级/评分'],
    skills: ['risk-review'],
    agents: ['risk-review'],
    outputs: ['riskLevel / riskScore', 'findings'],
  },
  {
    stage: 'PENDING_APPROVAL',
    label: '待审批',
    description: '按风险与套餐决定自动通过或 L1/L2 人工节点。',
    activities: ['approval-policy 决策', 'SLA 计时 / 升级'],
    skills: ['approval-policy'],
    agents: ['approval'],
    outputs: ['decision', 'requireRole / SLA'],
  },
  {
    stage: 'PROVISIONING',
    label: '资源开通',
    description: '审批通过后生成命名空间、配额与隔离 Schema 计划。',
    activities: ['provisioning 规则执行', '资源计划落库'],
    skills: ['provisioning'],
    agents: ['provisioning'],
    outputs: ['namespace / quota 计划'],
  },
  {
    stage: 'ACTIVE',
    label: '已生效',
    description: '租户进入正常服务状态，可使用组织、账号、评级与 MCP 能力。',
    activities: ['组织/账号日常管理', '评级周期评估', '配额与用量监控'],
    skills: ['risk-review'],
    agents: [],
    outputs: ['ACTIVE 租户', '评级与审计记录'],
  },
  {
    stage: 'SUSPENDED',
    label: '已停用',
    description: '租户暂停服务，可重新启用；数据保留。',
    activities: ['停用租户', '审计记录'],
    skills: [],
    agents: [],
    outputs: ['SUSPENDED 状态'],
  },
  {
    stage: 'TERMINATED',
    label: '已终止 / 离户',
    description: '终止并软删除租户，数据保留至保留期后清理。',
    activities: ['离户（offboard）', '数据保留期计时', 'TENANT_PURGED 清理'],
    skills: [],
    agents: [],
    outputs: ['TERMINATED + deleted_at', '审计 TENANT_OFFBOARDED'],
  },
]

export function WorkflowsPage() {
  const lifecycle = useAsync(async () => {
    try {
      const result = await api.get<WorkflowLifecycleView>('/api/workflows/tenant-lifecycle')
      if (Array.isArray(result?.stages) && result.stages.length) return result
      return { stages: STATIC_STAGES }
    } catch {
      return { stages: STATIC_STAGES }
    }
  }, [])

  const stages = lifecycle.data?.stages?.length ? lifecycle.data.stages : STATIC_STAGES
  // 后端未返回 updatedAt 或请求失败时，视为使用内置模板兜底
  const fallback = Boolean(lifecycle.error) || !lifecycle.data?.updatedAt

  return (
    <div className="page">
      <header className="page-head">
        <div className="page-head-main">
          <div>
            <h2>租户生命周期工作流</h2>
            <p>只读视图：阶段 → 活动 → 负责 Skill / Agent → 产出；数据优先来自 /api/workflows/tenant-lifecycle。</p>
          </div>
        </div>
        <div className="page-head-actions">
          <Badge tone="gray">只读</Badge>
          <button type="button" className="btn btn-ghost btn-sm" onClick={lifecycle.reload}>
            <Icon name="refresh" size={15} />
            刷新
          </button>
        </div>
      </header>

      <SectionCard
        title="生命周期阶段"
        description={
          fallback
            ? '后端接口未就绪或返回为空，当前展示内置生命周期模板（静态兜底）。'
            : `数据更新于 ${formatDateTime(lifecycle.data?.updatedAt)}`
        }
      >
        <ol className="workflow-list">
          {stages.map((stage, index) => (
            <li key={stage.stage} className="workflow-stage">
              <div className="workflow-index">{index + 1}</div>
              <div className="workflow-body">
                <header className="workflow-head">
                  <strong>{stage.label}</strong>
                  <span className="mono">{stage.stage}</span>
                  {index < stages.length - 1 ? (
                    <span className="workflow-arrow" aria-hidden="true">
                      →
                    </span>
                  ) : null}
                </header>
                {stage.description ? <p className="workflow-desc">{stage.description}</p> : null}
                <div className="workflow-columns">
                  <div>
                    <span className="workflow-label">活动</span>
                    <ul className="workflow-items">
                      {(stage.activities ?? []).map((activity) => (
                        <li key={activity}>{activity}</li>
                      ))}
                    </ul>
                  </div>
                  <div>
                    <span className="workflow-label">Skill / Agent</span>
                    <div className="chips">
                      {(stage.skills ?? []).map((skill) => (
                        <span key={skill} className="chip chip-blue">{skill}</span>
                      ))}
                      {(stage.agents ?? []).map((agent) => (
                        <span key={agent} className="chip">{agent}</span>
                      ))}
                      {!stage.skills?.length && !stage.agents?.length ? (
                        <span className="muted">无</span>
                      ) : null}
                    </div>
                  </div>
                  <div>
                    <span className="workflow-label">产出</span>
                    <div className="chips">
                      {(stage.outputs ?? []).map((output) => (
                        <span key={output} className="chip chip-ghost">{output}</span>
                      ))}
                    </div>
                  </div>
                </div>
              </div>
            </li>
          ))}
        </ol>
      </SectionCard>
    </div>
  )
}
