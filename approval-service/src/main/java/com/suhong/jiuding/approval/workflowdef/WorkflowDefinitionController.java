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
package com.suhong.jiuding.approval.workflowdef;

import com.suhong.jiuding.common.api.ApiResponse;
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
import java.util.Map;

/** Platform-admin workflow definition API (docs/23 §3). */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowDefinitionController {

    private final WorkflowDefinitionService service;

    public WorkflowDefinitionController(WorkflowDefinitionService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<WorkflowView>> list(@RequestParam(required = false) String code,
                                                @RequestParam(required = false) String status) {
        return ApiResponse.ok(service.list(code, status));
    }

    @PostMapping
    public ApiResponse<WorkflowView> create(@Valid @RequestBody WorkflowCreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<WorkflowView> get(@PathVariable String id) {
        return ApiResponse.ok(service.get(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<WorkflowView> update(@PathVariable String id,
                                            @RequestBody WorkflowUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String id) {
        service.delete(id);
        return ApiResponse.ok(Map.of("id", id, "deleted", true));
    }

    @PostMapping("/{id}/validate")
    public ApiResponse<WorkflowValidationResult> validate(@PathVariable String id) {
        return ApiResponse.ok(service.validate(id));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<WorkflowView> publish(@PathVariable String id) {
        return ApiResponse.ok(service.publish(id));
    }
}
