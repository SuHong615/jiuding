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
package com.suhong.jiuding.tenant.platformuser;

import com.suhong.jiuding.tenant.support.TimeUtil;

import java.util.List;

/** Successful platform user authentication result for the gateway (docs/20 section 3). */
public record PlatformUserAuthResponse(
        String id,
        String username,
        String displayName,
        String email,
        List<String> roles,
        String status,
        String lastLoginAt) {

    public static PlatformUserAuthResponse of(PlatformUserRow row) {
        return new PlatformUserAuthResponse(
                row.id(),
                row.username(),
                row.displayName(),
                row.email(),
                PlatformUserView.splitRoles(row.roles()),
                row.status(),
                TimeUtil.iso(row.lastLoginAt()));
    }
}
