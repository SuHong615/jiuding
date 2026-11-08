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
package com.suhong.jiuding.approval.api;

import com.suhong.jiuding.approval.service.ApprovalService;
import com.suhong.jiuding.common.api.ApiResponse;
import com.suhong.jiuding.common.api.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.List;

/** Approval workflow REST API (docs/02-api-contracts.md section 3). */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @PostMapping
    public ApiResponse<ApprovalView> create(@Valid @RequestBody ApprovalCreateRequest request) {
        return ApiResponse.ok(approvalService.create(request));
    }

    @GetMapping
    public ApiResponse<PageResult<ApprovalView>> list(@RequestParam(required = false) String state,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(approvalService.list(state, page, size));
    }

    @GetMapping("/pending")
    public ApiResponse<List<ApprovalView>> pending(@RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(approvalService.pending(size));
    }

    @GetMapping("/overview")
    public ApiResponse<ApprovalOverview> overview() {
        return ApiResponse.ok(approvalService.overview());
    }

    @GetMapping("/{id}")
    public ApiResponse<ApprovalView> get(@PathVariable String id) {
        return ApiResponse.ok(approvalService.get(id));
    }

    @PostMapping("/{id}/decisions")
    public ApiResponse<ApprovalView> decide(@PathVariable String id,
                                            @Valid @RequestBody DecisionRequest request) {
        return ApiResponse.ok(approvalService.decide(id, request.decision(), request.comment()));
    }

    @PostMapping("/{id}/escalate")
    public ApiResponse<ApprovalView> escalate(@PathVariable String id,
                                              @RequestBody(required = false) EscalateRequest request) {
        String reason = request == null || request.reason() == null || request.reason().isBlank()
                ? "manual escalation"
                : request.reason();
        return ApiResponse.ok(approvalService.escalate(id, reason));
    }
}
