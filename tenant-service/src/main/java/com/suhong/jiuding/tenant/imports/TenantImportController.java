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

import com.suhong.jiuding.common.api.ApiResponse;
import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.tenant.imports.CsvImportReader.CsvDataRow;
import com.suhong.jiuding.tenant.imports.CsvImportReader.CsvTable;
import com.suhong.jiuding.tenant.imports.ImportDtos.AccountImportItem;
import com.suhong.jiuding.tenant.imports.ImportDtos.AccountImportRequest;
import com.suhong.jiuding.tenant.imports.ImportDtos.ImportResult;
import com.suhong.jiuding.tenant.imports.ImportDtos.OrgUnitImportItem;
import com.suhong.jiuding.tenant.imports.ImportDtos.OrgUnitImportRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Bulk import API for organization units and accounts (docs/15 section 5).
 * Accepts either a multipart CSV file or a JSON {@code {items:[...]}} body.
 */
@RestController
@RequestMapping("/api/tenants/{tenantId}")
public class TenantImportController {

    private static final MediaType CSV = MediaType.valueOf("text/csv;charset=UTF-8");

    private static final String ORG_UNITS_TEMPLATE = """
            parentCode,code,name,type,sortOrder,managerUsername
            ,HQ,Headquarters,COMPANY,0,
            HQ,ENG,Engineering,DEPARTMENT,1,
            """;
    private static final String ACCOUNTS_TEMPLATE = """
            username,displayName,email,phone,orgCode,role
            alice,Alice Chen,alice@acme.io,13800000000,HQ,MEMBER
            """;

    private final TenantImportService importService;

    public TenantImportController(TenantImportService importService) {
        this.importService = importService;
    }

    @GetMapping(value = "/import-templates/org-units.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<String> orgUnitsTemplate(@PathVariable String tenantId) {
        importService.assertImportAllowed(tenantId);
        return attachment("org-units-template.csv", ORG_UNITS_TEMPLATE);
    }

    @GetMapping(value = "/import-templates/accounts.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<String> accountsTemplate(@PathVariable String tenantId) {
        importService.assertImportAllowed(tenantId);
        return attachment("accounts-template.csv", ACCOUNTS_TEMPLATE);
    }

    @PostMapping(value = "/org-units/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ImportResult> importOrgUnitsCsv(@PathVariable String tenantId,
                                                      @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(importService.importOrgUnits(tenantId, readOrgUnits(file)));
    }

    @PostMapping(value = "/org-units/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ImportResult> importOrgUnitsJson(@PathVariable String tenantId,
                                                        @RequestBody(required = false) OrgUnitImportRequest request) {
        return ApiResponse.ok(importService.importOrgUnits(tenantId, request == null ? null : request.items()));
    }

    @PostMapping(value = "/accounts/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ImportResult> importAccountsCsv(@PathVariable String tenantId,
                                                       @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(importService.importAccounts(tenantId, readAccounts(file)));
    }

    @PostMapping(value = "/accounts/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ImportResult> importAccountsJson(@PathVariable String tenantId,
                                                        @RequestBody(required = false) AccountImportRequest request) {
        return ApiResponse.ok(importService.importAccounts(tenantId, request == null ? null : request.items()));
    }

    // ------------------------------------------------------------------ CSV parsing

    private List<OrgUnitImportItem> readOrgUnits(MultipartFile file) {
        CsvTable table = readTable(file);
        if (table.column("code") < 0 || table.column("name") < 0 || table.column("type") < 0) {
            throw BizException.invalid("csv header must contain code, name and type columns");
        }
        List<OrgUnitImportItem> items = new ArrayList<>(table.rows().size());
        for (CsvDataRow row : table.rows()) {
            items.add(new OrgUnitImportItem(
                    table.value(row, "parentcode"),
                    table.value(row, "code"),
                    table.value(row, "name"),
                    table.value(row, "type"),
                    table.value(row, "sortorder"),
                    table.value(row, "managerusername")));
        }
        return items;
    }

    private List<AccountImportItem> readAccounts(MultipartFile file) {
        CsvTable table = readTable(file);
        if (table.column("username") < 0 || table.column("displayname") < 0) {
            throw BizException.invalid("csv header must contain username and displayName columns");
        }
        List<AccountImportItem> items = new ArrayList<>(table.rows().size());
        for (CsvDataRow row : table.rows()) {
            items.add(new AccountImportItem(
                    table.value(row, "username"),
                    table.value(row, "displayname"),
                    table.value(row, "email"),
                    table.value(row, "phone"),
                    table.value(row, "orgcode"),
                    table.value(row, "role")));
        }
        return items;
    }

    private CsvTable readTable(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BizException.invalid("csv file is required");
        }
        try {
            return CsvImportReader.read(file.getBytes());
        } catch (IOException ex) {
            throw BizException.invalid("cannot read uploaded csv file");
        }
    }

    private ResponseEntity<String> attachment(String fileName, String body) {
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(body);
    }
}
