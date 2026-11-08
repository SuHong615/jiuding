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

/** Outcome produced by the approval-policy decision step. */
public enum AutoDecision {
    AUTO_APPROVE,
    NEEDS_REVIEW,
    REJECT,
    NONE;

    public static AutoDecision parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        String normalized = raw.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "AUTO_APPROVE", "AUTO_APPROVED", "APPROVE", "AUTOAPPROVE" -> AUTO_APPROVE;
            case "NEEDS_REVIEW", "REVIEW", "ROUTE", "MANUAL_REVIEW", "NEEDS_APPROVAL" -> NEEDS_REVIEW;
            case "REJECT", "REJECTED", "DENY", "DENIED" -> REJECT;
            default -> NONE;
        };
    }
}
