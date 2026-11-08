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
package com.suhong.jiuding.tenant.service;

import com.suhong.jiuding.common.api.PageResult;
import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.error.ErrorCode;
import com.suhong.jiuding.common.tenant.TenantScope;
import com.suhong.jiuding.tenant.api.CollectJobView;
import com.suhong.jiuding.tenant.api.CollectRequest;
import com.suhong.jiuding.tenant.api.TenantCreateRequest;
import com.suhong.jiuding.tenant.config.TenantProperties;
import com.suhong.jiuding.tenant.domain.CollectMode;
import com.suhong.jiuding.tenant.domain.CollectStatus;
import com.suhong.jiuding.tenant.domain.TenantSource;
import com.suhong.jiuding.tenant.repository.CollectJobRepository;
import com.suhong.jiuding.tenant.repository.CollectJobRow;
import com.suhong.jiuding.tenant.repository.TenantRepository;
import com.suhong.jiuding.tenant.repository.TenantRow;
import com.suhong.jiuding.tenant.support.Paging;
import com.suhong.jiuding.tenant.support.SecurityScope;
import com.suhong.jiuding.tenant.support.Texts;
import com.suhong.jiuding.tenant.support.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * CSV / inline batch tenant collection.
 *
 * <p>Jobs are created synchronously (so the caller immediately gets a job id) and the
 * import itself runs on a dedicated thread pool. Duplicate {@code tenantCode} values are
 * skipped both against the database and within the batch. Per-row errors are capped so a
 * hostile file cannot bloat the database.
 */
@Service
public class CollectionService {

    private static final Logger log = LoggerFactory.getLogger(CollectionService.class);
    private static final int MAX_INLINE_RECORDS = 1000;
    private static final int MAX_ERROR_LENGTH = 500;

    private final CollectJobRepository jobs;
    private final TenantRepository tenants;
    private final TenantAssembler assembler;
    private final AuditService auditService;
    private final TenantService tenantService;
    private final TenantProperties properties;
    private final CsvTenantParser csvParser;
    private final Executor executor;

    public CollectionService(CollectJobRepository jobs,
                             TenantRepository tenants,
                             TenantAssembler assembler,
                             AuditService auditService,
                             TenantService tenantService,
                             TenantProperties properties,
                             CsvTenantParser csvParser,
                             @Qualifier("collectExecutor") Executor executor) {
        this.jobs = jobs;
        this.tenants = tenants;
        this.assembler = assembler;
        this.auditService = auditService;
        this.tenantService = tenantService;
        this.properties = properties;
        this.csvParser = csvParser;
        this.executor = executor;
    }

    /** POST /api/tenants/collect — inline JSON batch. */
    public CollectJobView startInline(CollectRequest request) {
        TenantScope scope = SecurityScope.require();
        SecurityScope.requirePlatformWide(scope);
        String actor = SecurityScope.actor(scope);

        if (request == null || request.records() == null || request.records().isEmpty()) {
            throw BizException.invalid("records must contain at least one tenant");
        }
        if (request.records().size() > MAX_INLINE_RECORDS) {
            throw BizException.invalid("at most " + MAX_INLINE_RECORDS + " records are allowed per batch");
        }
        CollectMode mode = CollectMode.parseOrDefault(request.mode());
        if (mode == CollectMode.CSV) {
            throw BizException.invalid("mode CSV must be uploaded through POST /api/tenants/collect/csv");
        }
        String sourceName = Texts.defaultIfBlank(request.sourceName(), "inline-batch");
        List<TenantCreateRequest> records = List.copyOf(request.records());
        boolean autoSubmit = Boolean.TRUE.equals(request.autoSubmit());

        CollectJobRow job = newJob(mode.name(), sourceName, records.size());
        jobs.insert(job);
        executor.execute(() -> runInlineJob(job.id(), records, sourceName, autoSubmit, actor));
        return reload(job.id());
    }

    /** POST /api/tenants/collect/csv — multipart upload. */
    public CollectJobView startCsv(MultipartFile file, String sourceName, Boolean autoSubmit) {
        TenantScope scope = SecurityScope.require();
        SecurityScope.requirePlatformWide(scope);
        String actor = SecurityScope.actor(scope);

        if (file == null || file.isEmpty()) {
            throw BizException.invalid("csv file is required");
        }
        long maxBytes = properties.getCollect().getCsvMaxBytes();
        if (file.getSize() > maxBytes) {
            throw BizException.invalid("csv file exceeds the " + maxBytes + " byte limit");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "cannot read uploaded csv file", ex);
        }
        CsvTenantParser.CsvDocument document = csvParser.parse(content, maxBytes);
        if (document.rows().isEmpty()) {
            throw BizException.invalid("csv file contains no data rows");
        }
        String resolvedSource = Texts.defaultIfBlank(sourceName, Texts.defaultIfBlank(file.getOriginalFilename(), "upload.csv"));
        boolean auto = Boolean.TRUE.equals(autoSubmit);

        CollectJobRow job = newJob(CollectMode.CSV.name(), resolvedSource, document.rows().size());
        jobs.insert(job);
        executor.execute(() -> runCsvJob(job.id(), document, resolvedSource, auto, actor));
        return reload(job.id());
    }

    public PageResult<CollectJobView> listJobs(int page, int size) {
        TenantScope scope = SecurityScope.require();
        // Jobs span tenants (no tenant_id column), so only platform-wide callers may read them.
        SecurityScope.requirePlatformWide(scope);
        Paging.validate(page, size, properties.getMaxPageSize());
        long total = jobs.count();
        List<CollectJobRow> rows = total == 0 ? List.of() : jobs.search(page, size);
        return PageResult.of(rows.stream().map(CollectJobView::of).toList(), total, page, size);
    }

    public CollectJobView getJob(String id) {
        TenantScope scope = SecurityScope.require();
        SecurityScope.requirePlatformWide(scope);
        return CollectJobView.of(jobs.findById(id)
                .orElseThrow(() -> BizException.notFound("collection job not found: " + id)));
    }

    // ------------------------------------------------------------------ job execution

    private void runInlineJob(String jobId, List<TenantCreateRequest> records, String sourceName,
                              boolean autoSubmit, String actor) {
        try {
            Stats stats = new Stats();
            Set<String> seenCodes = new HashSet<>();
            List<String> errors = new ArrayList<>();
            List<String> importedIds = new ArrayList<>();
            for (int index = 0; index < records.size(); index++) {
                importRecord(records.get(index), "record " + (index + 1), sourceName, actor,
                        seenCodes, stats, errors, importedIds);
            }
            finish(jobId, stats, errors);
            autoSubmit(importedIds, autoSubmit, actor);
        } catch (RuntimeException ex) {
            log.error("collection job {} crashed", jobId, ex);
            failJob(jobId, ex);
        }
    }

    private void runCsvJob(String jobId, CsvTenantParser.CsvDocument document, String sourceName,
                           boolean autoSubmit, String actor) {
        try {
            Stats stats = new Stats();
            Set<String> seenCodes = new HashSet<>();
            List<String> errors = new ArrayList<>();
            List<String> importedIds = new ArrayList<>();
            for (CsvTenantParser.CsvRow csvRow : document.rows()) {
                if (csvRow.error() != null) {
                    stats.failed++;
                    addError(errors, "line " + csvRow.line() + ": " + csvRow.error());
                    continue;
                }
                TenantCreateRequest request;
                try {
                    request = assembler.fromCsvRow(csvRow);
                } catch (BizException ex) {
                    stats.failed++;
                    addError(errors, "line " + csvRow.line() + ": " + ex.getMessage());
                    continue;
                }
                importRecord(request, "line " + csvRow.line(), sourceName, actor,
                        seenCodes, stats, errors, importedIds);
            }
            finish(jobId, stats, errors);
            autoSubmit(importedIds, autoSubmit, actor);
        } catch (RuntimeException ex) {
            log.error("collection job {} crashed", jobId, ex);
            failJob(jobId, ex);
        }
    }

    private void importRecord(TenantCreateRequest request, String label, String sourceName, String actor,
                              Set<String> seenCodes, Stats stats, List<String> errors, List<String> importedIds) {
        TenantRow row;
        try {
            row = assembler.build(request, TenantSource.IMPORT);
        } catch (BizException ex) {
            stats.failed++;
            addError(errors, label + ": " + ex.getMessage());
            return;
        } catch (RuntimeException ex) {
            stats.failed++;
            addError(errors, label + ": " + safeMessage(ex));
            return;
        }

        String tenantCode = row.tenantCode();
        if (seenCodes.contains(tenantCode) || tenants.findByCode(tenantCode).isPresent()) {
            stats.duplicated++;
            return;
        }
        try {
            tenants.insert(row);
            seenCodes.add(tenantCode);
            stats.imported++;
            importedIds.add(row.id());
            auditService.record(row.id(), actor, "IMPORTED",
                    "imported from " + sourceName + " (" + label + ")");
        } catch (DuplicateKeyException ex) {
            // A concurrent job imported the same code between the check and the insert.
            seenCodes.add(tenantCode);
            stats.duplicated++;
        } catch (RuntimeException ex) {
            stats.failed++;
            addError(errors, label + ": " + safeMessage(ex));
        }
    }

    private void finish(String jobId, Stats stats, List<String> errors) {
        CollectStatus status;
        if (stats.failed == 0 && stats.duplicated == 0) {
            status = CollectStatus.SUCCEEDED;
        } else if (stats.imported > 0) {
            status = CollectStatus.PARTIAL;
        } else {
            status = CollectStatus.FAILED;
        }
        jobs.updateResult(jobId, status.name(), stats.imported, stats.duplicated, stats.failed,
                List.copyOf(errors), TimeUtil.now());
    }

    private void failJob(String jobId, RuntimeException ex) {
        jobs.updateResult(jobId, CollectStatus.FAILED.name(), 0, 0, 1,
                List.of(Texts.truncate("job failed: " + safeMessage(ex), MAX_ERROR_LENGTH)),
                TimeUtil.now());
    }

    private void autoSubmit(List<String> importedIds, boolean autoSubmit, String actor) {
        if (!autoSubmit || importedIds.isEmpty()) {
            return;
        }
        for (String tenantId : importedIds) {
            try {
                tenantService.submitForCollection(tenantId, actor);
            } catch (RuntimeException ex) {
                // Auto submit is best effort: the tenant stays DRAFT and can be submitted manually.
                log.warn("auto submit failed for imported tenant {}: {}", tenantId, ex.toString());
            }
        }
    }

    private CollectJobRow newJob(String mode, String sourceName, int total) {
        return new CollectJobRow(UUID.randomUUID().toString(), mode, Texts.truncate(sourceName, 128),
                CollectStatus.RUNNING.name(), total, 0, 0, 0, List.of(), TimeUtil.now(), null);
    }

    private void addError(List<String> errors, String message) {
        int maxErrors = Math.max(1, properties.getCollect().getMaxErrors());
        if (errors.size() < maxErrors) {
            errors.add(Texts.truncate(message, MAX_ERROR_LENGTH));
        }
    }

    private CollectJobView reload(String jobId) {
        return CollectJobView.of(jobs.findById(jobId)
                .orElseThrow(() -> BizException.notFound("collection job not found: " + jobId)));
    }

    private String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    /** Mutable counters for one job execution. */
    private static final class Stats {
        private int imported;
        private int duplicated;
        private int failed;
    }
}
