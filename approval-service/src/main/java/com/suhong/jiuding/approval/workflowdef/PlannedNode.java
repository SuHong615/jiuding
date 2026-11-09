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

import com.suhong.jiuding.approval.domain.NodeState;
import com.suhong.jiuding.approval.domain.NodeType;

/**
 * One node generated from a workflow definition, ready to be persisted as an
 * {@code approval_nodes} row.
 */
public record PlannedNode(
        String nodeId,
        String nodeType,
        NodeType dbNodeType,
        NodeState state,
        String role,
        Integer slaHours,
        String comment) {
}
