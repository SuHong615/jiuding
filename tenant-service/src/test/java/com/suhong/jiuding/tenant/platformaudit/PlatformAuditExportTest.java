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

import com.suhong.jiuding.tenant.support.AbstractIdentityWebTest;
import com.suhong.jiuding.tenant.support.TimeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/21 section 2: CSV export escaping, attachment headers, audit trail and 10000 row cap. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformAuditExportTest extends AbstractIdentityWebTest {

    private String alpha;
    private String bravo;

    @BeforeEach
    void setUp() throws Exception {
        cleanIdentityDatabase();
        alpha = createTenant("t-alpha", "Alpha");
        bravo = createTenant("t-bravo", "Bravo");
        activateTenant(alpha);
        activateTenant(bravo);
        jdbc.update("DELETE FROM tenant_audits");
    }

    @Test
    void exportsRfc4180EscapedCsvAttachmentAndWritesExportAudit() throws Exception {
        insertAudit(alpha, "a,b", "ACCOUNT_CREATED", "has, comma");
        insertAudit(bravo, "carol", "ACCOUNT_UPDATED", "he said \"hi\"");
        insertAudit(alpha, "dave", "FIELD_CHANGED", "line1\nline2");
        insertAudit(bravo, "erin", "TENANT_UPDATED", "plain detail");

        MvcResult result = mockMvc.perform(get("/api/platform/audits/export")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        org.hamcrest.Matchers.containsString("text/csv")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("attachment"),
                                org.hamcrest.Matchers.containsString("platform-audits-"),
                                org.hamcrest.Matchers.containsString(".csv"))))
                .andReturn();

        String csv = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith(
                "at,tenantId,tenantCode,tenantName,actor,action,detail\n");
        assertThat(csv).contains("\"a,b\"");
        assertThat(csv).contains("\"has, comma\"");
        assertThat(csv).contains("\"he said \"\"hi\"\"\"");
        assertThat(csv).contains("\"line1\nline2\"");
        assertThat(csv).contains("t-alpha", "Alpha", "t-bravo", "Bravo");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audits"
                        + " WHERE tenant_id = 'platform' AND action = 'PLATFORM_AUDIT_EXPORTED'",
                Long.class)).isEqualTo(1L);
        String detail = jdbc.queryForObject("SELECT detail FROM tenant_audits"
                        + " WHERE tenant_id = 'platform' AND action = 'PLATFORM_AUDIT_EXPORTED'",
                String.class);
        assertThat(detail).contains("rows=4").doesNotContain("has, comma");
    }

    @Test
    void exportCapsAtTenThousandRowsAndRejectsOtherFormats() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        var now = TimeUtil.now();
        for (int index = 0; index < 10_005; index++) {
            rows.add(new Object[]{UUID.randomUUID().toString(), alpha, now.minusSeconds(index),
                    "bulk", "BULK_ACTION", "bulk " + index});
        }
        jdbc.batchUpdate("INSERT INTO tenant_audits (id, tenant_id, at, actor, action, detail)"
                + " VALUES (?,?,?,?,?,?)", rows);

        String csv = mockMvc.perform(get("/api/platform/audits/export")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // header + 10000 data rows, each terminated by a newline
        assertThat(csv.split("\n").length).isEqualTo(10_001);
        assertThat(csv).doesNotContain("bulk 10004");

        mockMvc.perform(get("/api/platform/audits/export?format=json")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isBadRequest());
    }

    private void insertAudit(String tenantId, String actor, String action, String detail) {
        jdbc.update("INSERT INTO tenant_audits (id, tenant_id, at, actor, action, detail)"
                        + " VALUES (?,?,?,?,?,?)",
                UUID.randomUUID().toString(), tenantId, TimeUtil.now(), actor, action, detail);
    }
}
