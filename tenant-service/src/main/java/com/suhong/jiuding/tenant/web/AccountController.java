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
import com.suhong.jiuding.tenant.api.AccountCreateRequest;
import com.suhong.jiuding.tenant.api.AccountCreateResult;
import com.suhong.jiuding.tenant.api.AccountResetPasswordRequest;
import com.suhong.jiuding.tenant.api.AccountStatusRequest;
import com.suhong.jiuding.tenant.api.AccountUpdateRequest;
import com.suhong.jiuding.tenant.api.AccountView;
import com.suhong.jiuding.tenant.api.ChangePasswordRequest;
import com.suhong.jiuding.tenant.api.LoginEventView;
import com.suhong.jiuding.tenant.api.ResetPasswordResult;
import com.suhong.jiuding.tenant.service.AccountService;
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

/** Tenant account directory API (docs/11 section 6.3, docs/14 section 3.1). */
@RestController
@RequestMapping("/api/tenants/{tenantId}/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public ApiResponse<PageResult<AccountView>> list(
            @PathVariable String tenantId,
            @RequestParam(required = false) String orgUnitId,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean mask,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponses.ok(accountService.list(tenantId, orgUnitId, role, status, keyword,
                page, size, mask));
    }

    @PostMapping
    public ApiResponse<AccountCreateResult> create(@PathVariable String tenantId,
                                                   @Valid @RequestBody AccountCreateRequest request) {
        return ApiResponses.ok(accountService.create(tenantId, request));
    }

    @GetMapping("/{id}")
    public ApiResponse<AccountView> get(@PathVariable String tenantId,
                                        @PathVariable String id,
                                        @RequestParam(defaultValue = "false") boolean mask) {
        return ApiResponses.ok(accountService.get(tenantId, id, mask));
    }

    @PutMapping("/{id}")
    public ApiResponse<AccountView> update(@PathVariable String tenantId,
                                           @PathVariable String id,
                                           @Valid @RequestBody AccountUpdateRequest request) {
        return ApiResponses.ok(accountService.update(tenantId, id, request));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<AccountView> changeStatus(@PathVariable String tenantId,
                                                 @PathVariable String id,
                                                 @Valid @RequestBody AccountStatusRequest request) {
        return ApiResponses.ok(accountService.changeStatus(tenantId, id, request));
    }

    @PostMapping("/{id}/reset-password")
    public ApiResponse<ResetPasswordResult> resetPassword(
            @PathVariable String tenantId,
            @PathVariable String id,
            @RequestBody(required = false) AccountResetPasswordRequest request) {
        return ApiResponses.ok(accountService.resetPassword(tenantId, id,
                request == null ? null : request.newPassword()));
    }

    /** Unlock a LOCKED account (docs/14 section 3.1). */
    @PostMapping("/{id}/unlock")
    public ApiResponse<AccountView> unlock(@PathVariable String tenantId, @PathVariable String id) {
        return ApiResponses.ok(accountService.unlock(tenantId, id));
    }

    /** Self-service password change (docs/14 section 3.1). */
    @PostMapping("/{id}/change-password")
    public ApiResponse<AccountView> changePassword(@PathVariable String tenantId,
                                                   @PathVariable String id,
                                                   @Valid @RequestBody ChangePasswordRequest request) {
        return ApiResponses.ok(accountService.changePassword(tenantId, id, request));
    }

    /** Login security trail for one account (docs/14 section 3.1). */
    @GetMapping("/{id}/login-events")
    public ApiResponse<PageResult<LoginEventView>> loginEvents(
            @PathVariable String tenantId,
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponses.ok(accountService.loginEvents(tenantId, id, page, size));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String tenantId, @PathVariable String id) {
        accountService.delete(tenantId, id);
        return ApiResponses.ok(null);
    }
}
