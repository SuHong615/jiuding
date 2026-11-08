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
package com.suhong.jiuding.tenant.imports;

import java.util.List;

/** DTOs of the bulk organization / account import API (docs/15 section 5). */
public final class ImportDtos {

    private ImportDtos() {
    }

    /** JSON body of {@code POST /api/tenants/{tid}/org-units/import}. */
    public record OrgUnitImportRequest(List<OrgUnitImportItem> items) {
    }

    public record OrgUnitImportItem(String parentCode, String code, String name, String type,
                                    String sortOrder, String managerUsername) {
    }

    /** JSON body of {@code POST /api/tenants/{tid}/accounts/import}. */
    public record AccountImportRequest(List<AccountImportItem> items) {
    }

    public record AccountImportItem(String username, String displayName, String email,
                                    String phone, String orgCode, String role) {
    }

    /** Aggregate result of an import run. */
    public record ImportResult(int total, int imported, int skipped, int failed,
                               List<ImportError> errors, List<TempPasswordRow> tempPasswords) {
        public static ImportResult empty() {
            return new ImportResult(0, 0, 0, 0, List.of(), List.of());
        }
    }

    /** One failed row (1-based row number as seen in the CSV file / JSON array). */
    public record ImportError(int row, String message) {
    }

    /** One-time temporary password issued for an imported account. */
    public record TempPasswordRow(int row, String username, String tempPassword) {
    }
}
