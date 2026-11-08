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
package com.suhong.jiuding.tenant.export;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant data export endpoint (docs/18 section 2): JSON attachment bundle, PII masked by
 * default, unmasked only for platform administrators.
 */
@RestController
@RequestMapping("/api/tenants/{tenantId}")
public class TenantExportController {

    private final TenantExportService exportService;

    public TenantExportController(TenantExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@PathVariable String tenantId,
                                         @RequestParam(defaultValue = "true") boolean mask) {
        TenantExportService.ExportResult result = exportService.export(tenantId, mask);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.fileName() + "\"")
                .body(result.content());
    }
}
