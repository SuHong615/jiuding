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
package com.suhong.jiuding.approval.domain;

/** Canonical workflow node names persisted in {@code approvals.current_node} / {@code approval_nodes.node}. */
public final class WorkflowNodes {

    public static final String RISK_CHECK = "RISK_CHECK";
    public static final String AUTO_DECISION = "AUTO_DECISION";
    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String PROVISIONING = "PROVISIONING";
    public static final String ACTIVE = "ACTIVE";

    /** Role assigned to a level-1 human approval node. */
    public static final String ROLE_L1 = "APPROVER";
    /** Role assigned to a level-2 human approval node / escalated node. */
    public static final String ROLE_L2 = "SENIOR_APPROVER";
    /** Role allowed to act on decisions. */
    public static final String ROLE_PLATFORM_ADMIN = "PLATFORM_ADMIN";

    private WorkflowNodes() {
    }
}
