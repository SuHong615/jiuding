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
package com.suhong.jiuding.tenant.web;

import com.suhong.jiuding.common.api.ApiResponse;
import com.suhong.jiuding.common.api.PageResult;
import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.tenant.api.AuditCsvExport;
import com.suhong.jiuding.tenant.api.AuditView;
import com.suhong.jiuding.tenant.api.OverviewView;
import com.suhong.jiuding.tenant.api.ReasonRequest;
import com.suhong.jiuding.tenant.api.TenantCreateRequest;
import com.suhong.jiuding.tenant.api.TenantView;
import com.suhong.jiuding.tenant.permission.RequirePermission;
import com.suhong.jiuding.tenant.service.TenantService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Public tenant lifecycle API (docs/02-api-contracts.md section 2). */
@RestController
@RequestMapping("/api/tenants")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PostMapping
    @RequirePermission("tenant:write")
    public ApiResponse<TenantView> create(@Valid @RequestBody TenantCreateRequest request) {
        return ApiResponses.ok(tenantService.create(request));
    }

    @GetMapping
    @RequirePermission({"tenant:read", "org:write"})
    public ApiResponse<PageResult<TenantView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String plan,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponses.ok(tenantService.list(status, plan, keyword, includeDeleted, page, size));
    }

    @GetMapping("/overview")
    @RequirePermission({"tenant:read", "org:write"})
    public ApiResponse<OverviewView> overview() {
        return ApiResponses.ok(tenantService.overview());
    }

    @GetMapping("/{id}")
    @RequirePermission({"tenant:read", "org:write"})
    public ApiResponse<TenantView> get(@PathVariable String id) {
        return ApiResponses.ok(tenantService.get(id));
    }

    @PutMapping("/{id}")
    @RequirePermission("tenant:write")
    public ApiResponse<TenantView> update(@PathVariable String id,
                                          @Valid @RequestBody TenantCreateRequest request) {
        return ApiResponses.ok(tenantService.update(id, request));
    }

    @PostMapping("/{id}/submit")
    @RequirePermission("tenant:write")
    public ApiResponse<TenantView> submit(@PathVariable String id) {
        return ApiResponses.ok(tenantService.submit(id));
    }

    @PostMapping("/{id}/suspend")
    @RequirePermission("tenant:write")
    public ApiResponse<TenantView> suspend(@PathVariable String id,
                                           @RequestBody(required = false) ReasonRequest body) {
        return ApiResponses.ok(tenantService.suspend(id, body == null ? null : body.reason()));
    }

    @PostMapping("/{id}/activate")
    @RequirePermission("tenant:write")
    public ApiResponse<TenantView> activate(@PathVariable String id) {
        return ApiResponses.ok(tenantService.activate(id));
    }

    @PostMapping("/{id}/terminate")
    @RequirePermission("tenant:write")
    public ApiResponse<TenantView> terminate(@PathVariable String id,
                                             @RequestBody(required = false) ReasonRequest body) {
        return ApiResponses.ok(tenantService.terminate(id, body == null ? null : body.reason()));
    }

    @GetMapping("/{id}/audits")
    @RequirePermission("audit:read")
    public ApiResponse<List<AuditView>> audits(@PathVariable String id) {
        return ApiResponses.ok(tenantService.audits(id));
    }

    /** Compliance CSV export (docs/14 section 3.2). */
    @GetMapping("/{id}/audits/export")
    @RequirePermission("audit:read")
    public ResponseEntity<byte[]> exportAudits(
            @PathVariable String id,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "csv") String format) {
        if (!"csv".equalsIgnoreCase(format == null ? "" : format.trim())) {
            throw BizException.invalid("only format=csv is supported");
        }
        AuditCsvExport export = tenantService.exportAudits(id, from, to);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + export.filename() + "\"")
                .body(export.content());
    }

    /** Soft delete (docs/11 section 6.1): platform administrator, non ACTIVE tenant only. */
    @DeleteMapping("/{id}")
    @RequirePermission("tenant:write")
    public ApiResponse<TenantView> delete(@PathVariable String id) {
        return ApiResponses.ok(tenantService.delete(id));
    }
}
