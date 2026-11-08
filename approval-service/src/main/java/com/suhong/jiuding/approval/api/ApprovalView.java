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
package com.suhong.jiuding.approval.api;

import java.util.List;

/** Full approval representation returned by the approval-service API. */
public record ApprovalView(
        String id,
        String tenantId,
        String tenantCode,
        String plan,
        String state,
        String currentNode,
        String riskLevel,
        Integer riskScore,
        boolean autoDecided,
        String decidedBy,
        String decisionComment,
        String slaDueAt,
        boolean escalated,
        List<String> reasons,
        List<NodeView> nodes,
        List<TimelineItem> timeline,
        String createdAt,
        String updatedAt) {
}
