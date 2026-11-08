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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.tenant.TenantScope;
import com.suhong.jiuding.tenant.api.AuditView;
import com.suhong.jiuding.tenant.api.RatingView;
import com.suhong.jiuding.tenant.api.TenantView;
import com.suhong.jiuding.tenant.repository.AccountRepository;
import com.suhong.jiuding.tenant.repository.AuditRepository;
import com.suhong.jiuding.tenant.repository.OrgUnitRepository;
import com.suhong.jiuding.tenant.repository.OrgUnitRow;
import com.suhong.jiuding.tenant.repository.RatingRepository;
import com.suhong.jiuding.tenant.repository.RatingRow;
import com.suhong.jiuding.tenant.repository.TenantRepository;
import com.suhong.jiuding.tenant.repository.TenantRow;
import com.suhong.jiuding.tenant.service.AuditService;
import com.suhong.jiuding.tenant.service.RatingService;
import com.suhong.jiuding.tenant.support.PiiMask;
import com.suhong.jiuding.tenant.support.SecurityScope;
import com.suhong.jiuding.tenant.support.TenantAccess;
import com.suhong.jiuding.tenant.support.Texts;
import com.suhong.jiuding.tenant.support.TimeUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tenant data export bundle (docs/18 section 2): tenant, org units, accounts, current rating
 * plus at most 100 history rows and at most 500 audit rows, optionally PII masked.
 *
 * <p>Visible to platform administrators, tenant administrators and auditors; only platform
 * administrators may request the unmasked export. Every export writes a
 * {@code TENANT_EXPORTED} audit entry that contains no PII.
 */
@Service
public class TenantExportService {

    private static final int MAX_RATING_HISTORY = 100;
    private static final int MAX_AUDITS = 500;
    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final TenantAccess access;
    private final TenantRepository tenants;
    private final OrgUnitRepository orgUnits;
    private final AccountRepository accounts;
    private final RatingService ratingService;
    private final RatingRepository ratings;
    private final AuditRepository auditRepository;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TenantExportService(TenantAccess access,
                               TenantRepository tenants,
                               OrgUnitRepository orgUnits,
                               AccountRepository accounts,
                               RatingService ratingService,
                               RatingRepository ratings,
                               AuditRepository auditRepository,
                               AuditService auditService,
                               JdbcTemplate jdbc,
                               ObjectMapper objectMapper) {
        this.access = access;
        this.tenants = tenants;
        this.orgUnits = orgUnits;
        this.accounts = accounts;
        this.ratingService = ratingService;
        this.ratings = ratings;
        this.auditRepository = auditRepository;
        this.auditService = auditService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Builds the JSON export bundle and its RFC6266 friendly download file name. */
    public ExportResult export(String tenantId, boolean mask) {
        TenantScope scope = SecurityScope.require();
        TenantRow tenant = access.requireTenant(tenantId);
        access.assertSameTenant(scope, tenant);
        boolean platform = SecurityScope.isPlatformWide(scope);
        boolean allowed = platform || scope.hasRole("TENANT_ADMIN") || scope.hasRole("AUDITOR");
        if (!allowed) {
            throw BizException.forbidden(
                    "platform administrator, tenant administrator or auditor role required");
        }
        if (!mask && !platform) {
            throw BizException.forbidden("mask=false requires a platform administrator");
        }

        int orgUnitCount = (int) orgUnits.countByTenant(tenant.id());
        int accountCount = (int) accounts.countByTenant(tenant.id());

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("exportedAt", TimeUtil.iso(TimeUtil.now()));
        bundle.put("tenant", TenantView.of(tenant, orgUnitCount, accountCount));
        bundle.put("orgUnits", orgUnitRows(tenant.id()));
        bundle.put("accounts", accountRows(tenant.id(), mask));
        bundle.put("rating", rating(tenant.id()));
        bundle.put("audits", auditRows(tenant.id()));

        String detail = "mask=" + mask
                + " orgUnits=" + orgUnitCount
                + " accounts=" + accountCount
                + " audits=" + ((List<?>) bundle.get("audits")).size();
        auditService.record(tenant.id(), SecurityScope.actor(scope), "TENANT_EXPORTED", detail);

        byte[] content;
        try {
            content = objectMapper.writeValueAsBytes(bundle);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot serialize tenant export bundle", ex);
        }
        String fileName = "tenant-" + tenant.tenantCode() + "-export-"
                + FILE_TIMESTAMP.format(TimeUtil.now()) + ".json";
        return new ExportResult(fileName, content);
    }

    private List<Map<String, Object>> orgUnitRows(String tenantId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OrgUnitRow row : orgUnits.list(tenantId, null, null)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id());
            item.put("parentId", row.parentId());
            item.put("code", row.code());
            item.put("name", row.name());
            item.put("type", row.type());
            item.put("path", row.path());
            item.put("depth", row.depth());
            item.put("sortOrder", row.sortOrder());
            item.put("managerAccountId", row.managerAccountId());
            rows.add(item);
        }
        return rows;
    }

    private List<Map<String, Object>> accountRows(String tenantId, boolean mask) {
        return jdbc.query("SELECT id, username, display_name, email, phone, role, org_unit_id,"
                        + " status, must_change_password, last_login_at, created_at"
                        + " FROM accounts WHERE tenant_id = ? AND deleted_at IS NULL"
                        + " ORDER BY username ASC",
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getString("id"));
                    item.put("username", rs.getString("username"));
                    item.put("displayName", rs.getString("display_name"));
                    String email = rs.getString("email");
                    String phone = rs.getString("phone");
                    item.put("email", mask ? PiiMask.email(email) : email);
                    item.put("phone", mask ? PiiMask.phone(phone) : phone);
                    item.put("role", rs.getString("role"));
                    item.put("orgUnitId", rs.getString("org_unit_id"));
                    item.put("status", rs.getString("status"));
                    item.put("mustChangePassword", rs.getBoolean("must_change_password"));
                    item.put("lastLoginAt",
                            TimeUtil.iso(rs.getObject("last_login_at", java.time.LocalDateTime.class)));
                    item.put("createdAt",
                            TimeUtil.iso(rs.getObject("created_at", java.time.LocalDateTime.class)));
                    return item;
                }, tenantId);
    }

    private Map<String, Object> rating(String tenantId) {
        RatingView current = null;
        try {
            current = ratingService.currentInternal(tenantId);
        } catch (BizException ex) {
            if (ex.errorCode() != com.suhong.jiuding.common.error.ErrorCode.NOT_FOUND) {
                throw ex;
            }
        }
        List<RatingRow> rows = ratings.historyFrom(tenantId, 0, MAX_RATING_HISTORY);
        List<RatingView> history = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            RatingRow previous = index + 1 < rows.size() ? rows.get(index + 1) : null;
            history.add(RatingView.of(rows.get(index), previous));
        }
        Map<String, Object> rating = new LinkedHashMap<>();
        rating.put("current", current);
        rating.put("history", history);
        return rating;
    }

    private List<AuditView> auditRows(String tenantId) {
        return auditRepository.findByTenant(tenantId, MAX_AUDITS).stream()
                .map(AuditView::of)
                .toList();
    }

    /** Export payload: suggested download file name plus the serialized JSON bytes. */
    public record ExportResult(String fileName, byte[] content) {
    }
}
