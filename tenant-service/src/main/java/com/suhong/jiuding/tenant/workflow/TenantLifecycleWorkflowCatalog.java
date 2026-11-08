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
package com.suhong.jiuding.tenant.workflow;

import com.suhong.jiuding.tenant.domain.TenantStateMachine;
import com.suhong.jiuding.tenant.domain.TenantStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static definition of the tenant lifecycle workflow (docs/20 section 4.5).
 *
 * <p>Two hard invariants are enforced by construction and covered by unit tests:
 * <ul>
 *   <li>states and transitions are derived from {@link TenantStatus} /
 *       {@link TenantStateMachine}, so they can never drift from runtime enforcement;</li>
 *   <li>every state has at least one activity and the activity skill ids are exactly the
 *       five built-in skill packages under {@code skills/} (tenant-intake, risk-review,
 *       approval-policy, provisioning, tenant-rating).</li>
 * </ul>
 *
 * <p>This is reference data only: no database table is needed and the definition is served
 * read only.
 */
public final class TenantLifecycleWorkflowCatalog {

    private static final List<WorkflowStateView> STATES = List.of(
            new WorkflowStateView("DRAFT", "草稿",
                    "租户已创建，尚未提交审批；可编辑基础信息、计划与配额", 1),
            new WorkflowStateView("SUBMITTED", "已提交",
                    "入驻申请已提交，等待风险审查与审批编排", 2),
            new WorkflowStateView("RISK_REVIEW", "风险审查中",
                    "风险规则引擎正在评估合规、行业与配额风险", 3),
            new WorkflowStateView("PENDING_APPROVAL", "待人工审批",
                    "按风险等级分流到 L1/L2 审批人，等待审批决策", 4),
            new WorkflowStateView("APPROVED", "审批通过",
                    "审批已通过，等待生成并执行资源开通计划", 5),
            new WorkflowStateView("PROVISIONING", "开通中",
                    "正在创建命名空间/schema、应用配额并绑定策略", 6),
            new WorkflowStateView("ACTIVE", "运行中",
                    "租户已激活，可正常使用平台能力并接受周期评级复核", 7),
            new WorkflowStateView("SUSPENDED", "已停用",
                    "因合规或欠费暂停服务，可恢复为运行中", 8),
            new WorkflowStateView("REJECTED", "已驳回",
                    "审批未通过；租户可修改资料后重新提交", 9),
            new WorkflowStateView("TERMINATED", "已终止",
                    "租户生命周期结束，进入离场归档与数据保留期", 10));

    /** Activity definition per state, aligned with the five built-in skills. */
    private static final List<WorkflowActivityView> ACTIVITIES = List.of(
            new WorkflowActivityView("DRAFT", "入驻材料校验", "tenant-intake", "租户入驻校验",
                    "tenant-intake",
                    List.of("归一化档案", "校验问题列表", "入驻结论"), "即时（创建时）"),
            new WorkflowActivityView("SUBMITTED", "风险规则审查", "risk-review", "租户风险审查",
                    "risk-review",
                    List.of("风险等级", "风险评分", "规则命中", "审批建议"), "5 分钟内"),
            new WorkflowActivityView("RISK_REVIEW", "风险复核", "risk-review", "租户风险审查",
                    "risk-review",
                    List.of("风险等级", "风险评分", "审批建议"), "30 分钟内"),
            new WorkflowActivityView("PENDING_APPROVAL", "审批策略决策", "approval-policy", "审批策略",
                    "approval",
                    List.of("审批决策", "所需角色", "SLA 小时数"), "L1 24 小时 / L2 8 小时"),
            new WorkflowActivityView("APPROVED", "资源开通计划", "provisioning", "资源开通计划",
                    "provisioning",
                    List.of("命名空间", "schema", "配额计划", "执行步骤"), "10 分钟内"),
            new WorkflowActivityView("PROVISIONING", "资源开通执行", "provisioning", "资源开通计划",
                    "provisioning",
                    List.of("命名空间", "schema", "配额计划", "执行结果"), "30 分钟内"),
            new WorkflowActivityView("ACTIVE", "周期评级复核", "tenant-rating", "租户评级",
                    "tenant-rating",
                    List.of("评级等级", "综合评分", "五维分", "分数变化"), "每日 03:00 周期复核"),
            new WorkflowActivityView("SUSPENDED", "停用后评级复核", "tenant-rating", "租户评级",
                    "tenant-rating",
                    List.of("评级等级", "综合评分", "复核原因"), "按需复核"),
            new WorkflowActivityView("REJECTED", "驳回原因归集", "approval-policy", "审批策略",
                    "approval",
                    List.of("驳回决策", "驳回原因"), "即时"),
            new WorkflowActivityView("TERMINATED", "离场评级归档", "tenant-rating", "租户评级",
                    "tenant-rating",
                    List.of("归档等级", "归档分数", "归档时间"), "按需归档"));

    /** Business trigger labels for the legal transition edges. */
    private static final Map<String, String> TRIGGERS = triggers();

    private TenantLifecycleWorkflowCatalog() {
    }

    /** @return an immutable snapshot of the workflow definition. */
    public static TenantLifecycleWorkflowView snapshot() {
        return new TenantLifecycleWorkflowView(STATES, ACTIVITIES, transitions());
    }

    public static List<WorkflowStateView> states() {
        return STATES;
    }

    public static List<WorkflowActivityView> activities() {
        return ACTIVITIES;
    }

    /** Transitions are generated from {@link TenantStateMachine#allowedTargets(TenantStatus)}. */
    public static List<WorkflowTransitionView> transitions() {
        List<WorkflowTransitionView> edges = new ArrayList<>();
        for (TenantStatus from : TenantStatus.values()) {
            for (TenantStatus to : TenantStateMachine.allowedTargets(from)) {
                edges.add(new WorkflowTransitionView(from.name(), to.name(),
                        TRIGGERS.getOrDefault(from.name() + "->" + to.name(), "状态流转")));
            }
        }
        return List.copyOf(edges);
    }

    private static Map<String, String> triggers() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("DRAFT->SUBMITTED", "提交入驻");
        map.put("DRAFT->TERMINATED", "放弃入驻并终止");
        map.put("SUBMITTED->RISK_REVIEW", "风险审查启动");
        map.put("SUBMITTED->PENDING_APPROVAL", "进入人工审批");
        map.put("SUBMITTED->APPROVED", "审批通过（自动或人工）");
        map.put("SUBMITTED->REJECTED", "驳回");
        map.put("SUBMITTED->TERMINATED", "终止租户");
        map.put("RISK_REVIEW->PENDING_APPROVAL", "审查完成，转人工审批");
        map.put("RISK_REVIEW->APPROVED", "审批通过");
        map.put("RISK_REVIEW->REJECTED", "驳回");
        map.put("RISK_REVIEW->TERMINATED", "终止租户");
        map.put("PENDING_APPROVAL->APPROVED", "审批通过");
        map.put("PENDING_APPROVAL->REJECTED", "审批驳回");
        map.put("PENDING_APPROVAL->TERMINATED", "终止租户");
        map.put("APPROVED->PROVISIONING", "开始资源开通");
        map.put("APPROVED->ACTIVE", "免开通直接激活");
        map.put("APPROVED->REJECTED", "审批撤销并驳回");
        map.put("APPROVED->TERMINATED", "终止租户");
        map.put("PROVISIONING->ACTIVE", "开通完成");
        map.put("PROVISIONING->REJECTED", "开通失败转驳回");
        map.put("PROVISIONING->TERMINATED", "终止租户");
        map.put("ACTIVE->SUSPENDED", "停用租户");
        map.put("ACTIVE->REJECTED", "合规复核驳回");
        map.put("ACTIVE->TERMINATED", "终止并离场");
        map.put("SUSPENDED->ACTIVE", "恢复启用");
        map.put("SUSPENDED->REJECTED", "合规复核驳回");
        map.put("SUSPENDED->TERMINATED", "终止并离场");
        map.put("REJECTED->SUBMITTED", "修改后重新提交");
        map.put("REJECTED->TERMINATED", "终止租户");
        return Map.copyOf(map);
    }
}
