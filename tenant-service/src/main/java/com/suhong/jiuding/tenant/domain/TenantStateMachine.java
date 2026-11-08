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

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Strict tenant lifecycle state machine.
 *
 * <p>Happy path: DRAFT -&gt; SUBMITTED -&gt; RISK_REVIEW -&gt; PENDING_APPROVAL -&gt; APPROVED
 * -&gt; PROVISIONING -&gt; ACTIVE. Side branches: REJECTED (from any non terminated state),
 * SUSPENDED (ACTIVE), TERMINATED (any non terminated state).
 *
 * <p>Re-applying the current status is treated as an idempotent no-op so that the
 * approval-service callback chain (APPROVED -&gt; PROVISIONING -&gt; ACTIVE) can retry
 * safely. Illegal transitions raise {@link ErrorCode#APPROVAL_INVALID_STATE}.
 */
public final class TenantStateMachine {

    private static final Map<TenantStatus, Set<TenantStatus>> ALLOWED = buildTransitions();

    private TenantStateMachine() {
    }

    private static Map<TenantStatus, Set<TenantStatus>> buildTransitions() {
        Map<TenantStatus, Set<TenantStatus>> map = new EnumMap<>(TenantStatus.class);
        map.put(TenantStatus.DRAFT, EnumSet.of(TenantStatus.SUBMITTED, TenantStatus.TERMINATED));
        map.put(TenantStatus.SUBMITTED, EnumSet.of(TenantStatus.RISK_REVIEW,
                TenantStatus.PENDING_APPROVAL, TenantStatus.APPROVED,
                TenantStatus.REJECTED, TenantStatus.TERMINATED));
        map.put(TenantStatus.RISK_REVIEW, EnumSet.of(TenantStatus.PENDING_APPROVAL,
                TenantStatus.APPROVED, TenantStatus.REJECTED, TenantStatus.TERMINATED));
        map.put(TenantStatus.PENDING_APPROVAL, EnumSet.of(TenantStatus.APPROVED,
                TenantStatus.REJECTED, TenantStatus.TERMINATED));
        map.put(TenantStatus.APPROVED, EnumSet.of(TenantStatus.PROVISIONING,
                TenantStatus.ACTIVE, TenantStatus.REJECTED, TenantStatus.TERMINATED));
        map.put(TenantStatus.PROVISIONING, EnumSet.of(TenantStatus.ACTIVE,
                TenantStatus.REJECTED, TenantStatus.TERMINATED));
        map.put(TenantStatus.ACTIVE, EnumSet.of(TenantStatus.SUSPENDED,
                TenantStatus.REJECTED, TenantStatus.TERMINATED));
        map.put(TenantStatus.SUSPENDED, EnumSet.of(TenantStatus.ACTIVE,
                TenantStatus.REJECTED, TenantStatus.TERMINATED));
        map.put(TenantStatus.REJECTED, EnumSet.of(TenantStatus.SUBMITTED,
                TenantStatus.TERMINATED));
        map.put(TenantStatus.TERMINATED, EnumSet.noneOf(TenantStatus.class));
        return Map.copyOf(map);
    }

    /** @return true when the transition is legal, including the idempotent same-state case. */
    public static boolean canTransition(TenantStatus from, TenantStatus to) {
        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            return true;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static void assertTransition(TenantStatus from, TenantStatus to) {
        if (!canTransition(from, to)) {
            throw new BizException(ErrorCode.APPROVAL_INVALID_STATE,
                    "illegal tenant status transition: " + from + " -> " + to);
        }
    }

    /** Submit is only allowed from DRAFT or a previously REJECTED tenant (not an idempotent no-op). */
    public static boolean canSubmit(TenantStatus from) {
        return from == TenantStatus.DRAFT || from == TenantStatus.REJECTED;
    }

    public static Set<TenantStatus> allowedTargets(TenantStatus from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }
}
