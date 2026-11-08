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
package com.suhong.jiuding.tenant.repository;

import java.time.LocalDateTime;

/**
 * Row projection of {@code accounts} (docs/11 section 3).
 *
 * <p>{@code passwordHash}/{@code passwordSalt} never leave the persistence/service layer:
 * the public API only serialises {@link com.suhong.jiuding.tenant.api.AccountView}.
 */
public record AccountRow(
        String id,
        String tenantId,
        String orgUnitId,
        String username,
        String displayName,
        String email,
        String phone,
        String role,
        String status,
        String passwordHash,
        String passwordSalt,
        String passwordFingerprint,
        boolean mustChangePassword,
        int failedAttempts,
        LocalDateTime lastLoginAt,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt,
        long version) {

    /** Backwards compatible constructor for callers that do not track the fingerprint yet. */
    public AccountRow(String id,
                      String tenantId,
                      String orgUnitId,
                      String username,
                      String displayName,
                      String email,
                      String phone,
                      String role,
                      String status,
                      String passwordHash,
                      String passwordSalt,
                      boolean mustChangePassword,
                      int failedAttempts,
                      LocalDateTime lastLoginAt,
                      String createdBy,
                      LocalDateTime createdAt,
                      LocalDateTime updatedAt,
                      LocalDateTime deletedAt,
                      long version) {
        this(id, tenantId, orgUnitId, username, displayName, email, phone, role, status,
                passwordHash, passwordSalt, null, mustChangePassword, failedAttempts, lastLoginAt,
                createdBy, createdAt, updatedAt, deletedAt, version);
    }
}
