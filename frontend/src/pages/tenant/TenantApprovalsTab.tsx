/*
 * Copyright 2026 suhong
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author suhong
 */
import { Link } from 'react-router-dom'
import { api } from '../../api/client'
import type { ApprovalView, PageResult } from '../../api/types'
import { pageItems } from '../../api/types'
import { useAsync } from '../../hooks/useAsync'
import { AsyncBlock } from '../../components/PageState'
import { Badge } from '../../components/Badge'
import { SectionCard } from '../../components/Forms'
import { Icon } from '../../components/Icon'
import { formatDateTime } from '../../utils/format'
import {
  approvalStateLabel,
  approvalStateTone,
  nodeLabel,
  planLabel,
  riskLabel,
  riskTone,
} from '../../utils/labels'

export function TenantApprovalsTab({ tenantId }: { tenantId: string }) {
  const approvals = useAsync(
    () => api.get<PageResult<ApprovalView>>(`/api/approvals?tenantId=${tenantId}&page=0&size=50`),
    [tenantId],
  )

  // 后端未支持 tenantId 过滤时，前端按 tenantId 兜底过滤
  const items = pageItems(approvals.data).filter(
    (approval) => !approval.tenantId || approval.tenantId === tenantId,
  )

  return (
    <SectionCard
      title="该租户的审批记录"
      description="来自 /api/approvals（按 tenantId 过滤）"
      actions={
        <button type="button" className="icon-btn" onClick={approvals.reload} title="刷新审批记录">
          <Icon name="refresh" size={15} />
        </button>
      }
    >
      <AsyncBlock
        loading={approvals.loading}
        error={approvals.error}
        onRetry={approvals.reload}
        empty={!items.length}
        emptyText="暂无审批记录"
        emptyHint="提交租户后会产生审批单"
      >
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>审批 ID</th>
                <th>计划</th>
                <th>风险</th>
                <th>状态</th>
                <th>当前节点</th>
                <th>创建时间</th>
                <th className="align-right">操作</th>
              </tr>
            </thead>
            <tbody>
              {items.map((approval) => (
                <tr key={approval.id}>
                  <td className="mono">{approval.id}</td>
                  <td>
                    <Badge tone="gray">{planLabel(approval.plan)}</Badge>
                  </td>
                  <td>
                    <Badge tone={riskTone(approval.riskLevel)}>{riskLabel(approval.riskLevel)}</Badge>
                  </td>
                  <td>
                    <Badge tone={approvalStateTone(approval.state)} dot>
                      {approvalStateLabel(approval.state)}
                    </Badge>
                  </td>
                  <td className="muted">{nodeLabel(approval.currentNode)}</td>
                  <td className="muted nowrap">{formatDateTime(approval.createdAt)}</td>
                  <td className="align-right">
                    <Link to={`/approvals/${approval.id}`} className="link-btn">
                      详情
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </AsyncBlock>
    </SectionCard>
  )
}
