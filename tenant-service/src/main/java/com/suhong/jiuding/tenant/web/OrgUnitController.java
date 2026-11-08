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
import com.suhong.jiuding.tenant.api.OrgUnitCreateRequest;
import com.suhong.jiuding.tenant.api.OrgUnitMoveRequest;
import com.suhong.jiuding.tenant.api.OrgUnitUpdateRequest;
import com.suhong.jiuding.tenant.api.OrgUnitView;
import com.suhong.jiuding.tenant.service.OrgUnitService;
import jakarta.validation.Valid;
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

/** Organisation tree API (docs/11 section 6.2). */
@RestController
@RequestMapping("/api/tenants/{tenantId}/org-units")
public class OrgUnitController {

    private final OrgUnitService orgUnitService;

    public OrgUnitController(OrgUnitService orgUnitService) {
        this.orgUnitService = orgUnitService;
    }

    @GetMapping
    public ApiResponse<List<OrgUnitView>> list(
            @PathVariable String tenantId,
            @RequestParam(defaultValue = "tree") String format,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponses.ok(orgUnitService.list(tenantId, format, status, keyword));
    }

    @PostMapping
    public ApiResponse<OrgUnitView> create(@PathVariable String tenantId,
                                           @Valid @RequestBody OrgUnitCreateRequest request) {
        return ApiResponses.ok(orgUnitService.create(tenantId, request));
    }

    @GetMapping("/{id}")
    public ApiResponse<OrgUnitView> get(@PathVariable String tenantId, @PathVariable String id) {
        return ApiResponses.ok(orgUnitService.get(tenantId, id));
    }

    @PutMapping("/{id}")
    public ApiResponse<OrgUnitView> update(@PathVariable String tenantId,
                                           @PathVariable String id,
                                           @Valid @RequestBody OrgUnitUpdateRequest request) {
        return ApiResponses.ok(orgUnitService.update(tenantId, id, request));
    }

    @PostMapping("/{id}/move")
    public ApiResponse<OrgUnitView> move(@PathVariable String tenantId,
                                         @PathVariable String id,
                                         @RequestBody OrgUnitMoveRequest request) {
        return ApiResponses.ok(orgUnitService.move(tenantId, id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String tenantId, @PathVariable String id) {
        orgUnitService.delete(tenantId, id);
        return ApiResponses.ok(null);
    }
}
