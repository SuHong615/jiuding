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
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class TenantCsvParserTest {

    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private final CsvTenantParser parser = new CsvTenantParser();

    @Test
    void parsesHeaderRowsBomAndQuotedFields() {
        String csv = "\uFEFFtenantCode,name,contactEmail,plan,quotaSeats\n"
                + "t-acme,\"Acme, Inc.\",ops@acme.io,PRO,25\n"
                + "t-beta,Beta,beta@beta.io,,\n";
        CsvTenantParser.CsvDocument document = parse(csv, MAX_BYTES);

        assertThat(document.rows()).hasSize(2);
        CsvTenantParser.CsvRow first = document.rows().get(0);
        assertThat(first.line()).isEqualTo(2);
        assertThat(first.error()).isNull();
        assertThat(first.values())
                .containsEntry("tenantcode", "t-acme")
                .containsEntry("name", "Acme, Inc.")
                .containsEntry("contactemail", "ops@acme.io")
                .containsEntry("plan", "PRO")
                .containsEntry("quotaseats", "25");

        CsvTenantParser.CsvRow second = document.rows().get(1);
        assertThat(second.line()).isEqualTo(3);
        assertThat(second.values()).containsEntry("tenantcode", "t-beta").containsEntry("plan", "");
    }

    @Test
    void supportsChineseUtf8Values() {
        String csv = "tenantCode,name,contactName\n"
                + "t-cn,上海示例科技有限公司,张三\n";
        CsvTenantParser.CsvDocument document = parse(csv, MAX_BYTES);
        assertThat(document.rows()).hasSize(1);
        assertThat(document.rows().get(0).values())
                .containsEntry("name", "上海示例科技有限公司")
                .containsEntry("contactname", "张三");
    }

    @Test
    void missingRequiredHeaderIsRejected() {
        BizException ex = parseError("tenantCode,plan\nt-x,PRO\n");
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(ex.getMessage()).contains("tenantCode", "name");
    }

    @Test
    void oversizePayloadIsRejected() {
        BizException ex = parseError("tenantCode,name\nt-x,X\n", 5);
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(ex.getMessage()).contains("byte limit");
    }

    @Test
    void emptyContentIsRejected() {
        BizException ex = catchThrowableOfType(() -> parser.parse(new byte[0], MAX_BYTES), BizException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
    }

    @Test
    void headerOnlyDocumentHasNoRows() {
        CsvTenantParser.CsvDocument document = parse("tenantCode,name\n", MAX_BYTES);
        assertThat(document.rows()).isEmpty();
    }

    @Test
    void tooManyColumnsMarksRowAsStructurallyInvalid() {
        String csv = "tenantCode,name\nt-x,X,extra,more\n";
        CsvTenantParser.CsvDocument document = parse(csv, MAX_BYTES);
        assertThat(document.rows()).hasSize(1);
        assertThat(document.rows().get(0).error()).isNotNull().contains("columns");
        assertThat(document.rows().get(0).values()).isEmpty();
    }

    @Test
    void blankLinesAndUnknownColumnsAreIgnored() {
        String csv = "tenantCode,name,unknownColumn\n"
                + "\n"
                + "t-x,X,whatever\n"
                + "\n";
        CsvTenantParser.CsvDocument document = parse(csv, MAX_BYTES);
        assertThat(document.rows()).hasSize(1);
        assertThat(document.rows().get(0).values())
                .containsEntry("tenantcode", "t-x")
                .doesNotContainKey("unknowncolumn");
    }

    @Test
    void quotedFieldsCanContainNewlines() {
        String csv = "tenantCode,name\n"
                + "t-x,\"Line1\nLine2\"\n";
        CsvTenantParser.CsvDocument document = parse(csv, MAX_BYTES);
        assertThat(document.rows()).hasSize(1);
        assertThat(document.rows().get(0).values().get("name")).isEqualTo("Line1\nLine2");
    }

    private CsvTenantParser.CsvDocument parse(String csv, long maxBytes) {
        return parser.parse(csv.getBytes(StandardCharsets.UTF_8), maxBytes);
    }

    private BizException parseError(String csv) {
        return parseError(csv, MAX_BYTES);
    }

    private BizException parseError(String csv, long maxBytes) {
        return catchThrowableOfType(
                () -> parser.parse(csv.getBytes(StandardCharsets.UTF_8), maxBytes), BizException.class);
    }
}
