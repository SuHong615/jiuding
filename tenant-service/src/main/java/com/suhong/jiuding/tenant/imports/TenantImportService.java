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

import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.tenant.TenantScope;
import com.suhong.jiuding.tenant.api.AccountCreateRequest;
import com.suhong.jiuding.tenant.api.AccountCreateResult;
import com.suhong.jiuding.tenant.api.OrgUnitCreateRequest;
import com.suhong.jiuding.tenant.api.OrgUnitView;
import com.suhong.jiuding.tenant.imports.ImportDtos.AccountImportItem;
import com.suhong.jiuding.tenant.imports.ImportDtos.ImportError;
import com.suhong.jiuding.tenant.imports.ImportDtos.ImportResult;
import com.suhong.jiuding.tenant.imports.ImportDtos.OrgUnitImportItem;
import com.suhong.jiuding.tenant.imports.ImportDtos.TempPasswordRow;
import com.suhong.jiuding.tenant.repository.AccountRepository;
import com.suhong.jiuding.tenant.repository.AccountRow;
import com.suhong.jiuding.tenant.repository.OrgUnitRepository;
import com.suhong.jiuding.tenant.repository.OrgUnitRow;
import com.suhong.jiuding.tenant.repository.TenantRepository;
import com.suhong.jiuding.tenant.repository.TenantRow;
import com.suhong.jiuding.tenant.service.AccountService;
import com.suhong.jiuding.tenant.service.AuditService;
import com.suhong.jiuding.tenant.service.OrgUnitService;
import com.suhong.jiuding.tenant.support.TenantAccess;
import com.suhong.jiuding.tenant.support.Texts;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bulk organization unit / account import (docs/15 section 5).
 *
 * <p>Only PLATFORM_ADMIN and TENANT_ADMIN may import; ORG_ADMIN is rejected with 403.
 * Import is best effort per row: one bad row never aborts the batch. Existing codes /
 * usernames are skipped, organization units are created in parentCode topological order, and
 * account rows receive one-time temporary passwords.
 */
@Service
public class TenantImportService {

    private static final int MAX_ROWS = 1000;

    private final TenantAccess access;
    private final TenantRepository tenants;
    private final OrgUnitRepository orgUnits;
    private final AccountRepository accounts;
    private final OrgUnitService orgUnitService;
    private final AccountService accountService;
    private final AuditService auditService;

    public TenantImportService(TenantAccess access,
                               TenantRepository tenants,
                               OrgUnitRepository orgUnits,
                               AccountRepository accounts,
                               OrgUnitService orgUnitService,
                               AccountService accountService,
                               AuditService auditService) {
        this.access = access;
        this.tenants = tenants;
        this.orgUnits = orgUnits;
        this.accounts = accounts;
        this.orgUnitService = orgUnitService;
        this.accountService = accountService;
        this.auditService = auditService;
    }

    /** Guards the template download endpoints with the same permissions as the imports. */
    public TenantRow assertImportAllowed(String tenantId) {
        return begin(tenantId).tenant();
    }

    // ------------------------------------------------------------------ organization import

    public ImportResult importOrgUnits(String tenantId, List<OrgUnitImportItem> items) {
        ImportContext context = begin(tenantId);
        List<OrgUnitImportItem> rows = items == null ? List.of() : items;
        validateRowCount(rows.size());

        int imported = 0;
        int skipped = 0;
        int failed = 0;
        List<ImportError> errors = new ArrayList<>();

        Map<String, String> codeToId = new LinkedHashMap<>();
        for (OrgUnitRow existing : orgUnits.list(context.tenant().id(), null, null)) {
            codeToId.put(existing.code(), existing.id());
        }

        List<Numbered<OrgUnitImportItem>> pending = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            pending.add(new Numbered<>(index + 1, rows.get(index)));
        }

        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            Iterator<Numbered<OrgUnitImportItem>> iterator = pending.iterator();
            while (iterator.hasNext()) {
                Numbered<OrgUnitImportItem> numbered = iterator.next();
                OrgUnitImportItem item = numbered.item();
                String parentCode = item == null ? null : Texts.blankToNull(item.parentCode());
                if (parentCode != null && !codeToId.containsKey(parentCode)) {
                    // Parent might still be created later in this file (topological passes).
                    continue;
                }
                iterator.remove();
                progress = true;
                try {
                    if (item == null) {
                        throw BizException.invalid("row is empty");
                    }
                    String code = Texts.blankToNull(item.code());
                    String name = Texts.blankToNull(item.name());
                    String type = Texts.blankToNull(item.type());
                    if (code == null) {
                        throw BizException.invalid("code is required");
                    }
                    if (name == null) {
                        throw BizException.invalid("name is required");
                    }
                    if (type == null) {
                        throw BizException.invalid("type is required");
                    }
                    if (codeToId.containsKey(code)) {
                        skipped++;
                        continue;
                    }
                    String managerAccountId = resolveManager(context.tenant().id(), item.managerUsername());
                    Integer sortOrder = parseSortOrder(item.sortOrder());
                    OrgUnitCreateRequest request = new OrgUnitCreateRequest(
                            parentCode == null ? null : codeToId.get(parentCode),
                            code, name, type, sortOrder, managerAccountId);
                    OrgUnitView view = orgUnitService.create(context.tenant().id(), request);
                    codeToId.put(code, view.id());
                    imported++;
                } catch (BizException ex) {
                    failed++;
                    errors.add(new ImportError(numbered.rowNumber(), ex.getMessage()));
                } catch (RuntimeException ex) {
                    failed++;
                    errors.add(new ImportError(numbered.rowNumber(), safeMessage(ex)));
                }
            }
        }
        for (Numbered<OrgUnitImportItem> remaining : pending) {
            failed++;
            String parentCode = remaining.item() == null ? null
                    : Texts.blankToNull(remaining.item().parentCode());
            errors.add(new ImportError(remaining.rowNumber(),
                    "unknown or cyclic parentCode: " + parentCode));
        }

        auditService.record(context.tenant().id(), context.actor(), "IMPORT_ORG_UNITS",
                "org unit import: total=" + rows.size() + ", imported=" + imported
                        + ", skipped=" + skipped + ", failed=" + failed);
        return new ImportResult(rows.size(), imported, skipped, failed, List.copyOf(errors), List.of());
    }

    // ------------------------------------------------------------------ account import

    public ImportResult importAccounts(String tenantId, List<AccountImportItem> items) {
        ImportContext context = begin(tenantId);
        List<AccountImportItem> rows = items == null ? List.of() : items;
        validateRowCount(rows.size());

        int imported = 0;
        int skipped = 0;
        int failed = 0;
        List<ImportError> errors = new ArrayList<>();
        List<TempPasswordRow> tempPasswords = new ArrayList<>();

        Map<String, String> orgCodeToId = new LinkedHashMap<>();
        for (OrgUnitRow existing : orgUnits.list(context.tenant().id(), null, null)) {
            orgCodeToId.put(existing.code(), existing.id());
        }

        for (int index = 0; index < rows.size(); index++) {
            int rowNumber = index + 1;
            AccountImportItem item = rows.get(index);
            try {
                if (item == null) {
                    throw BizException.invalid("row is empty");
                }
                String username = Texts.blankToNull(item.username());
                String displayName = Texts.blankToNull(item.displayName());
                if (username == null) {
                    throw BizException.invalid("username is required");
                }
                if (displayName == null) {
                    throw BizException.invalid("displayName is required");
                }
                String normalized = username.toLowerCase(Locale.ROOT);
                if (accounts.findByUsername(context.tenant().id(), normalized).isPresent()) {
                    skipped++;
                    continue;
                }
                String orgUnitId = resolveOrgUnitId(context.tenant().id(), orgCodeToId, item.orgCode());
                AccountCreateRequest request = new AccountCreateRequest(
                        normalized,
                        displayName,
                        Texts.blankToNull(item.email()),
                        Texts.blankToNull(item.phone()),
                        orgUnitId,
                        Texts.blankToNull(item.role()),
                        null);
                AccountCreateResult result = accountService.create(context.tenant().id(), request);
                imported++;
                if (result.tempPassword() != null) {
                    tempPasswords.add(new TempPasswordRow(rowNumber,
                            result.account().username(), result.tempPassword()));
                }
            } catch (BizException ex) {
                failed++;
                errors.add(new ImportError(rowNumber, ex.getMessage()));
            } catch (RuntimeException ex) {
                failed++;
                errors.add(new ImportError(rowNumber, safeMessage(ex)));
            }
        }

        auditService.record(context.tenant().id(), context.actor(), "IMPORT_ACCOUNTS",
                "account import: total=" + rows.size() + ", imported=" + imported
                        + ", skipped=" + skipped + ", failed=" + failed
                        + ", tempPasswords=" + tempPasswords.size());
        return new ImportResult(rows.size(), imported, skipped, failed,
                List.copyOf(errors), List.copyOf(tempPasswords));
    }

    // ------------------------------------------------------------------ helpers

    private ImportContext begin(String tenantId) {
        TenantScope scope = access.requireScope();
        // Bulk imports are restricted to PLATFORM_ADMIN / TENANT_ADMIN (ORG_ADMIN -> 403).
        access.requireWrite(scope);
        TenantRow tenant = access.requireTenant(tenantId);
        access.assertSameTenant(scope, tenant);
        return new ImportContext(tenant, access.actor(scope));
    }

    private void validateRowCount(int size) {
        if (size == 0) {
            throw BizException.invalid("items must contain at least one row");
        }
        if (size > MAX_ROWS) {
            throw BizException.invalid("at most " + MAX_ROWS + " rows are allowed per import");
        }
    }

    private String resolveManager(String tenantId, String managerUsername) {
        String username = Texts.blankToNull(managerUsername);
        if (username == null) {
            return null;
        }
        AccountRow manager = accounts.findByUsername(tenantId, username.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> BizException.invalid("managerUsername not found: " + username));
        return manager.id();
    }

    private String resolveOrgUnitId(String tenantId, Map<String, String> orgCodeToId, String orgCode) {
        String code = Texts.blankToNull(orgCode);
        if (code == null) {
            return null;
        }
        String id = orgCodeToId.get(code);
        if (id == null) {
            throw BizException.invalid("orgCode not found: " + code);
        }
        return id;
    }

    private Integer parseSortOrder(String raw) {
        String value = Texts.blankToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw BizException.invalid("sortOrder must be an integer, got '" + raw + "'");
        }
    }

    private String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private record Numbered<T>(int rowNumber, T item) {
    }

    private record ImportContext(TenantRow tenant, String actor) {
    }
}
