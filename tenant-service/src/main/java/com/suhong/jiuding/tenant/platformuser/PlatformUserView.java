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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.suhong.jiuding.tenant.support.Texts;
import com.suhong.jiuding.tenant.support.TimeUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Platform user projection (docs/20 section 3). Password material is never exposed;
 * {@code temporaryPassword} is only present on create / reset responses.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlatformUserView(
        String id,
        String username,
        String displayName,
        String email,
        List<String> roles,
        String status,
        String createdAt,
        String updatedAt,
        String lastLoginAt,
        String temporaryPassword) {

    public static PlatformUserView of(PlatformUserRow row) {
        return of(row, null);
    }

    public static PlatformUserView of(PlatformUserRow row, String temporaryPassword) {
        return new PlatformUserView(
                row.id(),
                row.username(),
                row.displayName(),
                row.email(),
                splitRoles(row.roles()),
                row.status(),
                TimeUtil.iso(row.createdAt()),
                TimeUtil.iso(row.updatedAt()),
                TimeUtil.iso(row.lastLoginAt()),
                temporaryPassword);
    }

    static List<String> splitRoles(String roles) {
        String raw = Texts.blankToNull(roles);
        if (raw == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : raw.split(",")) {
            String role = part.trim();
            if (!role.isEmpty()) {
                result.add(role);
            }
        }
        return result;
    }

    static String joinRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return "";
        }
        return String.join(",", roles.stream()
                .map(role -> role == null ? "" : role.trim())
                .filter(role -> !role.isEmpty())
                .distinct()
                .toList());
    }
}
