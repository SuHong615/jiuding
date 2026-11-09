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
package com.suhong.jiuding.approval.workflowdef;

import com.suhong.jiuding.approval.service.AutoDecisionOutcome;

import java.util.List;

/**
 * Result of executing a published workflow definition for one approval.
 *
 * @param nodes          nodes to persist (in execution order, up to the stop point)
 * @param decision       auto decision when an AUTO_CHECK(approval-policy) ran, otherwise null
 * @param waitingAtHuman true when execution stopped at a HUMAN node
 * @param humanNodeId    persisted node name of the pending human node
 * @param humanRole      role required to decide the pending human node
 * @param humanSlaHours  SLA of the pending human node
 * @param definitionCode published workflow code
 * @param definitionVersion published workflow version
 * @param annotations    engine notes (PARALLEL sequential execution, ...)
 */
public record WorkflowPlan(
        List<PlannedNode> nodes,
        AutoDecisionOutcome decision,
        boolean waitingAtHuman,
        String humanNodeId,
        String humanRole,
        Integer humanSlaHours,
        String definitionCode,
        int definitionVersion,
        List<String> annotations) {
}
