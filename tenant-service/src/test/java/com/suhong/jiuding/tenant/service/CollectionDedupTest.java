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

import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.error.ErrorCode;
import com.suhong.jiuding.tenant.api.CollectJobView;
import com.suhong.jiuding.tenant.api.CollectRequest;
import com.suhong.jiuding.tenant.api.TenantCreateRequest;
import com.suhong.jiuding.tenant.repository.AuditRepository;
import com.suhong.jiuding.tenant.repository.TenantQuery;
import com.suhong.jiuding.tenant.repository.TenantRepository;
import com.suhong.jiuding.tenant.support.TestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest
@ActiveProfiles("test")
class CollectionDedupTest {

    @Autowired
    private CollectionService collectionService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private AuditRepository auditRepository;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        TestSecurity.platformAdmin();
        cleanDatabase();
    }

    @AfterEach
    void tearDown() {
        TestSecurity.clear();
    }

    @Test
    void deduplicatesAgainstDatabaseAndWithinBatch() {
        tenantService.create(request("t-existing", "Existing", "existing@acme.io"));

        CollectJobView job = collectionService.startInline(new CollectRequest(
                "batch-1", "MANUAL_BATCH", false, List.of(
                request("t-existing", "Database Duplicate", "dup@acme.io"),
                request("t-new", "New One", "new@acme.io"),
                request("t-new", "Batch Duplicate", "new2@acme.io"),
                request("bad code", "Invalid Code", "bad@acme.io"))));

        assertThat(job.status()).isEqualTo("PARTIAL");
        assertThat(job.total()).isEqualTo(4);
        assertThat(job.imported()).isEqualTo(1);
        assertThat(job.duplicated()).isEqualTo(2);
        assertThat(job.failed()).isEqualTo(1);
        assertThat(job.errors()).hasSize(1);
        assertThat(job.errors().get(0)).contains("record 4");
        assertThat(job.finishedAt()).isNotBlank();

        assertThat(tenantRepository.findByCode("t-new")).isPresent();
        assertThat(tenantRepository.count(null, TenantQuery.empty())).isEqualTo(2);
        // Every imported tenant receives an audit entry.
        String importedId = tenantRepository.findByCode("t-new").orElseThrow().id();
        assertThat(auditRepository.findByTenant(importedId, 10))
                .anyMatch(audit -> "IMPORTED".equals(audit.action()));
    }

    @Test
    void allInvalidRecordsProduceFailedJob() {
        CollectJobView job = collectionService.startInline(new CollectRequest(
                null, null, false, List.of(
                request("", "No code", null),
                request("t-ok", "", null),
                request("t-ok2", "Bad Email", "not-an-email"))));

        assertThat(job.status()).isEqualTo("FAILED");
        assertThat(job.imported()).isZero();
        assertThat(job.failed()).isEqualTo(3);
        assertThat(job.errors()).hasSize(3);
    }

    @Test
    void errorListIsCapped() {
        List<TenantCreateRequest> records = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            records.add(request("", "invalid-" + index, null));
        }
        CollectJobView job = collectionService.startInline(
                new CollectRequest("cap", "MANUAL_BATCH", false, records));

        assertThat(job.status()).isEqualTo("FAILED");
        assertThat(job.failed()).isEqualTo(25);
        assertThat(job.errors()).hasSize(20);
    }

    @Test
    void csvJobImportsDeduplicatesAndReportsFailures() {
        tenantService.create(request("t-beta", "Beta", "beta@acme.io"));
        String csv = "tenantCode,name,contactEmail,plan\n"
                + "t-csv-1,Csv One,csv1@acme.io,PRO\n"
                + "t-beta,Csv Duplicate,beta@acme.io,FREE\n"
                + "t-csv-2,Csv Bad,not-an-email,PRO\n";
        MockMultipartFile file = new MockMultipartFile("file", "tenants.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        CollectJobView job = collectionService.startCsv(file, "csv-batch", false);

        assertThat(job.mode()).isEqualTo("CSV");
        assertThat(job.sourceName()).isEqualTo("csv-batch");
        assertThat(job.total()).isEqualTo(3);
        assertThat(job.imported()).isEqualTo(1);
        assertThat(job.duplicated()).isEqualTo(1);
        assertThat(job.failed()).isEqualTo(1);
        assertThat(job.status()).isEqualTo("PARTIAL");
        assertThat(job.errors()).hasSize(1);
        assertThat(tenantRepository.findByCode("t-csv-1")).isPresent();
    }

    @Test
    void autoSubmitDegradesGracefullyWhenApprovalServiceIsDown() {
        CollectJobView job = collectionService.startInline(new CollectRequest(
                "auto", "MANUAL_BATCH", true, List.of(request("t-auto", "Auto Submit", "auto@acme.io"))));

        assertThat(job.status()).isEqualTo("SUCCEEDED");
        assertThat(job.imported()).isEqualTo(1);
        // approval-service is unreachable in the test profile: tenant must stay SUBMITTED, not 5xx.
        assertThat(tenantRepository.findByCode("t-auto").orElseThrow().status()).isEqualTo("SUBMITTED");
    }

    @Test
    void collectionIsFailClosedWithoutTenantScope() {
        TestSecurity.clear();
        BizException ex = catchThrowableOfType(() -> collectionService.startInline(
                new CollectRequest("x", "MANUAL_BATCH", false,
                        List.of(request("t-fail", "Fail Closed", null)))), BizException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    void jobListingIsPlatformOnly() {
        CollectJobView job = collectionService.startInline(new CollectRequest(
                "visible", "MANUAL_BATCH", false, List.of(request("t-visible", "Visible", null))));
        assertThat(collectionService.listJobs(0, 10).total()).isEqualTo(1);
        assertThat(collectionService.getJob(job.id()).id()).isEqualTo(job.id());

        TestSecurity.tenantAdmin("t-visible");
        BizException ex = catchThrowableOfType(() -> collectionService.listJobs(0, 10), BizException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
    }

    private TenantCreateRequest request(String code, String name, String email) {
        return new TenantCreateRequest(code, name, null, email, null, null, null, null,
                null, null, null, null, null, false);
    }

    private void cleanDatabase() {
        jdbc.update("DELETE FROM tenant_audits");
        jdbc.update("DELETE FROM collect_jobs");
        jdbc.update("DELETE FROM tenants");
    }
}
