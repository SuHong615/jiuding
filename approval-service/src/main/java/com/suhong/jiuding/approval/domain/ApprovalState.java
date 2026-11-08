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

/** States of the approval workflow (docs/02-api-contracts.md section 3). */
public enum ApprovalState {
    RISK_CHECK,
    AUTO_DECISION,
    AUTO_APPROVED,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    PROVISIONING,
    ACTIVE;

    /** A tenant may not receive a second approval while one of these states is live. */
    public boolean isActiveForTenant() {
        return switch (this) {
            case RISK_CHECK, AUTO_DECISION, AUTO_APPROVED, PENDING_APPROVAL, APPROVED, PROVISIONING -> true;
            case REJECTED, ACTIVE -> false;
        };
    }

    public boolean isTerminal() {
        return this == REJECTED || this == ACTIVE;
    }
}
