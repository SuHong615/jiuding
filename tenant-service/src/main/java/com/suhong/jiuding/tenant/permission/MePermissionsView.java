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
package com.suhong.jiuding.tenant.permission;

import java.util.List;

/**
 * Response of {@code GET /api/me/permissions} (docs/15 section 7.2).
 *
 * <p>Platform administrators receive {@code ["*"]} so the frontend can treat them as
 * unrestricted; tenant accounts receive the union of their role permission codes.
 */
public record MePermissionsView(List<String> roles, List<String> permissions) {
}
