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

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** DTO from docs/02-api-contracts.md section 3 (v12 adds an optional workflow code). */
public record ApprovalCreateRequest(
        @NotBlank(message = "tenantId is required") String tenantId,
        @NotBlank(message = "tenantCode is required") String tenantCode,
        String plan,
        String riskLevel,
        Integer riskScore,
        List<String> reasons,
        String requestedBy,
        String workflowCode) {
}
