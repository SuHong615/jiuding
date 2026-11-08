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
package com.suhong.jiuding.tenant.api;

import java.util.Map;

/** Aggregated tenant counters for dashboards ({@code GET /api/tenants/overview}). */
public record OverviewView(
        long total,
        long active,
        long pending,
        Map<String, Long> byStatus,
        Map<String, Long> byPlan,
        Map<String, Long> byRisk) {
}
