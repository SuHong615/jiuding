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
package com.suhong.jiuding.approval.service;

import com.suhong.jiuding.approval.domain.AutoDecision;

/**
 * Outcome of the auto-decision step.
 *
 * @param decision     AUTO_APPROVE / NEEDS_REVIEW / REJECT
 * @param requiredRole role of the human node when a review is required (may be null for auto decisions)
 * @param slaHours     SLA of the human node when a review is required (0 for auto decisions)
 * @param source       {@code agent} when agent-service answered, {@code local-fallback} otherwise
 * @param detail       human readable reason persisted in the node comment / timeline
 */
public record AutoDecisionOutcome(
        AutoDecision decision,
        String requiredRole,
        int slaHours,
        String source,
        String detail) {
}
