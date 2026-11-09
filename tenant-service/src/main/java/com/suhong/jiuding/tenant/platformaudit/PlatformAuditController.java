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
package com.suhong.jiuding.tenant.platformaudit;

import com.suhong.jiuding.common.api.ApiResponse;
import com.suhong.jiuding.common.api.PageResult;
import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.tenant.permission.RequirePermission;
import com.suhong.jiuding.tenant.support.Texts;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform audit center API (docs/21 section 2), platform administrator JWT only. */
@RestController
@RequestMapping("/api/platform/audits")
public class PlatformAuditController {

    private final PlatformAuditService auditService;

    public PlatformAuditController(PlatformAuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    @RequirePermission("audit:read")
    public ApiResponse<PageResult<PlatformAuditView>> list(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(auditService.search(tenantId, action, from, to, keyword, page, size),
                MDC.get("requestId"));
    }

    @GetMapping("/export")
    @RequirePermission("audit:read")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "csv") String format) {
        String resolvedFormat = Texts.blankToNull(format);
        if (resolvedFormat != null && !"csv".equalsIgnoreCase(resolvedFormat)) {
            throw BizException.invalid("only format=csv is supported");
        }
        PlatformAuditCsvExport export = auditService.exportCsv(tenantId, action, from, to, keyword);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + export.filename() + "\"")
                .body(export.content());
    }
}
