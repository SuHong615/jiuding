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
package com.suhong.jiuding.tenant.domain;

import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class TenantStateMachineTest {

    @Test
    void happyPathIsFullyLegal() {
        TenantStatus[] path = {
                TenantStatus.DRAFT, TenantStatus.SUBMITTED, TenantStatus.RISK_REVIEW,
                TenantStatus.PENDING_APPROVAL, TenantStatus.APPROVED,
                TenantStatus.PROVISIONING, TenantStatus.ACTIVE};
        for (int index = 0; index < path.length - 1; index++) {
            assertThat(TenantStateMachine.canTransition(path[index], path[index + 1]))
                    .as("%s -> %s", path[index], path[index + 1])
                    .isTrue();
        }
    }

    @Test
    void sideBranchesAreLegal() {
        assertThat(TenantStateMachine.canTransition(TenantStatus.ACTIVE, TenantStatus.SUSPENDED)).isTrue();
        assertThat(TenantStateMachine.canTransition(TenantStatus.SUSPENDED, TenantStatus.ACTIVE)).isTrue();
        assertThat(TenantStateMachine.canTransition(TenantStatus.RISK_REVIEW, TenantStatus.REJECTED)).isTrue();
        assertThat(TenantStateMachine.canTransition(TenantStatus.PENDING_APPROVAL, TenantStatus.REJECTED)).isTrue();
        assertThat(TenantStateMachine.canTransition(TenantStatus.REJECTED, TenantStatus.SUBMITTED)).isTrue();
        for (TenantStatus status : TenantStatus.values()) {
            if (status != TenantStatus.TERMINATED) {
                assertThat(TenantStateMachine.canTransition(status, TenantStatus.TERMINATED))
                        .as("%s -> TERMINATED", status).isTrue();
            }
        }
    }

    @Test
    void sameStateReapplicationIsIdempotent() {
        for (TenantStatus status : TenantStatus.values()) {
            assertThat(TenantStateMachine.canTransition(status, status)).as("idempotent %s", status).isTrue();
            TenantStateMachine.assertTransition(status, status);
        }
    }

    @Test
    void illegalTransitionsRaiseApprovalInvalidState() {
        assertIllegal(TenantStatus.DRAFT, TenantStatus.ACTIVE);
        assertIllegal(TenantStatus.DRAFT, TenantStatus.APPROVED);
        assertIllegal(TenantStatus.SUBMITTED, TenantStatus.SUSPENDED);
        assertIllegal(TenantStatus.PENDING_APPROVAL, TenantStatus.ACTIVE);
        assertIllegal(TenantStatus.PROVISIONING, TenantStatus.SUSPENDED);
        assertIllegal(TenantStatus.SUSPENDED, TenantStatus.PROVISIONING);
        assertIllegal(TenantStatus.TERMINATED, TenantStatus.ACTIVE);
        assertIllegal(TenantStatus.TERMINATED, TenantStatus.REJECTED);
    }

    @Test
    void terminalStateHasNoOutgoingTargets() {
        assertThat(TenantStateMachine.allowedTargets(TenantStatus.TERMINATED)).isEmpty();
        assertThat(TenantStateMachine.canTransition(TenantStatus.TERMINATED, TenantStatus.TERMINATED)).isTrue();
    }

    @Test
    void onlyDraftAndRejectedCanBeSubmitted() {
        assertThat(TenantStateMachine.canSubmit(TenantStatus.DRAFT)).isTrue();
        assertThat(TenantStateMachine.canSubmit(TenantStatus.REJECTED)).isTrue();
        for (TenantStatus status : TenantStatus.values()) {
            if (status != TenantStatus.DRAFT && status != TenantStatus.REJECTED) {
                assertThat(TenantStateMachine.canSubmit(status)).as("submit from %s", status).isFalse();
            }
        }
    }

    @Test
    void statusParsingRejectsUnknownValues() {
        assertThat(TenantStatus.parse("active")).isEqualTo(TenantStatus.ACTIVE);
        assertThat(TenantStatus.parse(" PENDING_APPROVAL ")).isEqualTo(TenantStatus.PENDING_APPROVAL);
        BizException blank = catchThrowableOfType(() -> TenantStatus.parse(" "), BizException.class);
        assertThat(blank).isNotNull();
        assertThat(blank.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        BizException unknown = catchThrowableOfType(() -> TenantStatus.parse("NOPE"), BizException.class);
        assertThat(unknown).isNotNull();
        assertThat(unknown.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
    }

    private void assertIllegal(TenantStatus from, TenantStatus to) {
        BizException ex = catchThrowableOfType(
                () -> TenantStateMachine.assertTransition(from, to), BizException.class);
        assertThat(ex).as("%s -> %s should be illegal", from, to).isNotNull();
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.APPROVAL_INVALID_STATE);
    }
}
