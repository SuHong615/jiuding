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

import com.suhong.jiuding.common.api.PageResult;
import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.tenant.TenantScope;
import com.suhong.jiuding.tenant.config.TenantProperties;
import com.suhong.jiuding.tenant.permission.RbacService;
import com.suhong.jiuding.tenant.service.AuditService;
import com.suhong.jiuding.tenant.support.Paging;
import com.suhong.jiuding.tenant.support.SecurityScope;
import com.suhong.jiuding.tenant.support.Texts;
import com.suhong.jiuding.tenant.support.TimeUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cross-tenant audit center (docs/21 section 2). Only platform administrator JWTs may query or
 * export; the tenant-scoped audit endpoints remain the only read path for tenant
 * administrators and auditors.
 *
 * <p>Filters: tenant id/code, action, ISO-8601 time range (default last 30 days), keyword
 * (actor/action/detail/tenant code/tenant name), page/size. Rows are ordered {@code at DESC}.
 * The CSV export is capped at 10000 rows and is itself audited as
 * {@code PLATFORM_AUDIT_EXPORTED}.
 */
@Service
public class PlatformAuditService {

    private static final int MAX_EXPORT_ROWS = 10_000;
    private static final int DEFAULT_WINDOW_DAYS = 30;
    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final RowMapper<PlatformAuditView> ROW_MAPPER = (rs, rowNum) ->
            PlatformAuditView.of(
                    rs.getString("id"),
                    rs.getString("tenant_id"),
                    rs.getString("tenant_code"),
                    rs.getString("tenant_name"),
                    rs.getObject("at", LocalDateTime.class),
                    rs.getString("actor"),
                    rs.getString("action"),
                    rs.getString("detail"));

    private final JdbcTemplate jdbc;
    private final TenantProperties properties;
    private final AuditService auditService;

    public PlatformAuditService(JdbcTemplate jdbc, TenantProperties properties,
                                AuditService auditService) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.auditService = auditService;
    }

    public PageResult<PlatformAuditView> search(String tenantId, String action, String fromRaw,
                                                String toRaw, String keyword, int page, int size) {
        requirePlatformAdmin();
        Paging.validate(page, size, properties.getMaxPageSize());
        Filters filters = filters(tenantId, action, fromRaw, toRaw, keyword);
        List<Object> args = new ArrayList<>();
        String where = whereClause(filters, args);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audits a"
                + " LEFT JOIN tenants t ON t.id = a.tenant_id" + where, Long.class, args.toArray());
        List<PlatformAuditView> items = jdbc.query("SELECT a.id, a.tenant_id, t.tenant_code,"
                        + " t.name AS tenant_name, a.at, a.actor, a.action, a.detail"
                        + " FROM tenant_audits a LEFT JOIN tenants t ON t.id = a.tenant_id"
                        + where + " ORDER BY a.at DESC, a.id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, append(args, size, (long) page * size).toArray());
        return PageResult.of(items, total == null ? 0L : total, page, size);
    }

    public PlatformAuditCsvExport exportCsv(String tenantId, String action, String fromRaw,
                                            String toRaw, String keyword) {
        TenantScope scope = requirePlatformAdmin();
        Filters filters = filters(tenantId, action, fromRaw, toRaw, keyword);
        List<Object> args = new ArrayList<>();
        String where = whereClause(filters, args);
        List<PlatformAuditView> rows = jdbc.query("SELECT a.id, a.tenant_id, t.tenant_code,"
                        + " t.name AS tenant_name, a.at, a.actor, a.action, a.detail"
                        + " FROM tenant_audits a LEFT JOIN tenants t ON t.id = a.tenant_id"
                        + where + " ORDER BY a.at DESC, a.id DESC LIMIT ?",
                ROW_MAPPER, append(args, MAX_EXPORT_ROWS).toArray());
        String csv = toCsv(rows);
        auditService.record(RbacService.PLATFORM_AUDIT_TENANT, SecurityScope.actor(scope),
                "PLATFORM_AUDIT_EXPORTED", "rows=" + rows.size() + " filters=" + filters.describe());
        String fileName = "platform-audits-" + FILE_TIMESTAMP.format(TimeUtil.now()) + ".csv";
        return new PlatformAuditCsvExport(csv.getBytes(StandardCharsets.UTF_8), fileName);
    }

    // ------------------------------------------------------------------ helpers

    private TenantScope requirePlatformAdmin() {
        TenantScope scope = SecurityScope.require();
        SecurityScope.requireJwtPlatformAdmin(scope);
        return scope;
    }

    private Filters filters(String tenantId, String action, String fromRaw, String toRaw,
                            String keyword) {
        LocalDateTime to = parseIsoOrDefault(toRaw, TimeUtil.now());
        LocalDateTime from = parseIsoOrDefault(fromRaw, to.minusDays(DEFAULT_WINDOW_DAYS));
        if (from.isAfter(to)) {
            throw BizException.invalid("from must be before to");
        }
        return new Filters(Texts.blankToNull(tenantId), Texts.blankToNull(action),
                from, to, Texts.blankToNull(keyword));
    }

    private String whereClause(Filters filters, List<Object> args) {
        StringBuilder where = new StringBuilder(" WHERE a.at >= ? AND a.at <= ?");
        args.add(filters.from());
        args.add(filters.to());
        if (filters.tenantId() != null) {
            where.append(" AND (a.tenant_id = ? OR t.tenant_code = ?)");
            args.add(filters.tenantId());
            args.add(filters.tenantId());
        }
        if (filters.action() != null) {
            where.append(" AND a.action = ?");
            args.add(filters.action());
        }
        if (filters.keyword() != null) {
            String pattern = "%" + filters.keyword().toLowerCase(Locale.ROOT) + "%";
            where.append(" AND (LOWER(a.actor) LIKE ? OR LOWER(a.action) LIKE ?"
                    + " OR LOWER(a.detail) LIKE ? OR LOWER(t.tenant_code) LIKE ?"
                    + " OR LOWER(t.name) LIKE ?)");
            for (int index = 0; index < 5; index++) {
                args.add(pattern);
            }
        }
        return where.toString();
    }

    private List<Object> append(List<Object> args, Object... extra) {
        List<Object> result = new ArrayList<>(args);
        for (Object value : extra) {
            result.add(value);
        }
        return result;
    }

    private String toCsv(List<PlatformAuditView> rows) {
        StringBuilder csv = new StringBuilder(
                "at,tenantId,tenantCode,tenantName,actor,action,detail\n");
        for (PlatformAuditView row : rows) {
            csv.append(csvCell(row.at())).append(',')
                    .append(csvCell(row.tenantId())).append(',')
                    .append(csvCell(row.tenantCode())).append(',')
                    .append(csvCell(row.tenantName())).append(',')
                    .append(csvCell(row.actor())).append(',')
                    .append(csvCell(row.action())).append(',')
                    .append(csvCell(row.detail())).append('\n');
        }
        return csv.toString();
    }

    /** RFC4180 style escaping: quote when the value contains comma, quote, CR or LF. */
    private static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static LocalDateTime parseIsoOrDefault(String raw, LocalDateTime fallback) {
        String value = Texts.blankToNull(raw);
        if (value == null) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(value).toInstant()
                    .atZone(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // not an offset timestamp, retry as local date time
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ex) {
            throw BizException.invalid("invalid ISO-8601 timestamp: " + raw);
        }
    }

    private record Filters(String tenantId, String action, LocalDateTime from, LocalDateTime to,
                           String keyword) {

        String describe() {
            return "tenantId=" + (tenantId == null ? "" : tenantId)
                    + ",action=" + (action == null ? "" : action)
                    + ",from=" + TimeUtil.iso(from)
                    + ",to=" + TimeUtil.iso(to)
                    + ",keyword=" + (keyword == null ? "" : keyword);
        }
    }
}
