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
package com.suhong.jiuding.tenant.onboarding;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Public self-service onboarding submission (docs/15 section 6.2). */
public record OnboardingApplyRequest(
        @NotBlank
        @Size(max = 128)
        String companyName,
        @Size(max = 64)
        String tenantCode,
        @Size(max = 64)
        String contactName,
        @Email
        @Size(max = 128)
        String contactEmail,
        @Size(max = 32)
        String contactPhone,
        @Size(max = 16)
        String plan,
        @Size(max = 32)
        String region,
        @Size(max = 64)
        String industry,
        @Size(max = 512)
        String notes) {
}
