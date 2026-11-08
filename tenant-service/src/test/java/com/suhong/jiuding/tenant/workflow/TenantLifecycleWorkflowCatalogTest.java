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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/20 section 4.5: the workflow reference data must stay aligned with the enforced
 * lifecycle state machine and with the five built-in skill packages.
 */
class TenantLifecycleWorkflowCatalogTest {

    private static final Map<String, String> SKILL_AGENT = Map.of(
            "tenant-intake", "tenant-intake",
            "risk-review", "risk-review",
            "approval-policy", "approval",
            "provisioning", "provisioning",
            "tenant-rating", "tenant-rating");

    private static final List<TenantStatus> HAPPY_PATH = List.of(
            TenantStatus.DRAFT, TenantStatus.SUBMITTED, TenantStatus.RISK_REVIEW,
            TenantStatus.PENDING_APPROVAL, TenantStatus.APPROVED,
            TenantStatus.PROVISIONING, TenantStatus.ACTIVE);

    @Test
    void statesFollowTheLifecycleStateMachine() {
        TenantLifecycleWorkflowView snapshot = TenantLifecycleWorkflowCatalog.snapshot();
        List<String> expectedCodes = new ArrayList<>();
        for (TenantStatus status : TenantStatus.values()) {
            expectedCodes.add(status.name());
        }

        assertThat(snapshot.states()).hasSize(expectedCodes.size());
        assertThat(snapshot.states().stream().map(WorkflowStateView::code).toList())
                .containsExactlyElementsOf(expectedCodes);
        assertThat(snapshot.states()).allSatisfy(state -> {
            assertThat(state.name()).isNotBlank();
            assertThat(state.description()).isNotBlank();
        });
        assertThat(snapshot.states()).extracting(WorkflowStateView::order)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(snapshot.states()).extracting(WorkflowStateView::name).doesNotHaveDuplicates();
    }

    @Test
    void everyStateHasAnActivityAndAllFiveSkillsAreReferenced() {
        TenantLifecycleWorkflowView snapshot = TenantLifecycleWorkflowCatalog.snapshot();
        Set<String> stateCodes = new LinkedHashSet<>();
        snapshot.states().forEach(state -> stateCodes.add(state.code()));

        Set<String> coveredStates = new HashSet<>();
        for (WorkflowActivityView activity : snapshot.activities()) {
            assertThat(stateCodes).contains(activity.state());
            coveredStates.add(activity.state());
            assertThat(activity.activity()).isNotBlank();
            assertThat(activity.skillName()).isNotBlank();
            assertThat(activity.sla()).isNotBlank();
            assertThat(activity.outputs()).isNotEmpty().allSatisfy(output ->
                    assertThat(output).isNotBlank());
            assertThat(SKILL_AGENT).containsKey(activity.skillId());
            assertThat(activity.agentType()).isEqualTo(SKILL_AGENT.get(activity.skillId()));
        }
        assertThat(coveredStates).containsExactlyInAnyOrderElementsOf(stateCodes);

        Set<String> referencedSkills = new HashSet<>();
        snapshot.activities().forEach(activity -> referencedSkills.add(activity.skillId()));
        assertThat(referencedSkills).containsExactlyInAnyOrderElementsOf(SKILL_AGENT.keySet());
    }

    @Test
    void transitionsExactlyMatchTheStateMachine() {
        TenantLifecycleWorkflowView snapshot = TenantLifecycleWorkflowCatalog.snapshot();

        Set<String> expectedEdges = new HashSet<>();
        for (TenantStatus from : TenantStatus.values()) {
            for (TenantStatus to : TenantStateMachine.allowedTargets(from)) {
                expectedEdges.add(from.name() + "->" + to.name());
            }
        }
        Set<String> actualEdges = new HashSet<>();
        for (WorkflowTransitionView transition : snapshot.transitions()) {
            assertThat(TenantStatus.valueOf(transition.from())).isNotNull();
            assertThat(TenantStatus.valueOf(transition.to())).isNotNull();
            assertThat(transition.from()).isNotEqualTo(transition.to());
            assertThat(transition.trigger()).isNotBlank();
            actualEdges.add(transition.from() + "->" + transition.to());
        }
        assertThat(snapshot.transitions()).hasSize(expectedEdges.size());
        assertThat(actualEdges).isEqualTo(expectedEdges);
    }

    @Test
    void happyPathAndTerminalStateRulesAreVisible() {
        TenantLifecycleWorkflowView snapshot = TenantLifecycleWorkflowCatalog.snapshot();
        List<String> edges = snapshot.transitions().stream()
                .map(transition -> transition.from() + "->" + transition.to())
                .toList();
        for (int index = 0; index < HAPPY_PATH.size() - 1; index++) {
            String edge = HAPPY_PATH.get(index).name() + "->" + HAPPY_PATH.get(index + 1).name();
            assertThat(edges).as("happy path edge %s", edge).contains(edge);
        }
        assertThat(edges).contains("ACTIVE->SUSPENDED", "SUSPENDED->ACTIVE",
                "REJECTED->SUBMITTED");
        assertThat(snapshot.transitions().stream()
                .filter(transition -> "TERMINATED".equals(transition.from()))
                .toList()).isEmpty();
    }

    @Test
    void snapshotIsStableReferenceData() {
        TenantLifecycleWorkflowView first = TenantLifecycleWorkflowCatalog.snapshot();
        TenantLifecycleWorkflowView second = TenantLifecycleWorkflowCatalog.snapshot();
        assertThat(second).isEqualTo(first);
        assertThat(first.transitions()).isEqualTo(second.transitions());
    }
}
