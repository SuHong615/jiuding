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
package com.suhong.jiuding.tenant.permission;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** docs/15 section 7.1: v9 semantic aliases resolve onto the existing v5 permission codes. */
class PermissionCatalogTest {

    @Test
    void aliasesMapToTheSeededPermissionCodes() {
        assertThat(PermissionCatalog.codesOf("tenant:read")).containsExactly("TENANT_READ");
        assertThat(PermissionCatalog.codesOf("org:read")).containsExactly("ORG_READ");
        assertThat(PermissionCatalog.codesOf("org:write")).containsExactlyInAnyOrder(
                "ORG_CREATE", "ORG_UPDATE", "ORG_MOVE", "ORG_DELETE");
        assertThat(PermissionCatalog.codesOf("account:security")).containsExactlyInAnyOrder(
                "ACCOUNT_STATUS", "ACCOUNT_PASSWORD_RESET", "ACCOUNT_DELETE", "ACCOUNT_UNLOCK");
        assertThat(PermissionCatalog.codesOf("rating:assess")).containsExactly("RATING_ASSESS");
        assertThat(PermissionCatalog.codesOf("rating:override")).containsExactly("RATING_OVERRIDE");
        assertThat(PermissionCatalog.codesOf("rbac:write")).containsExactly("RBAC_MANAGE");
        assertThat(PermissionCatalog.codesOf("audit:read")).containsExactlyInAnyOrder(
                "TENANT_AUDIT_READ", "TENANT_AUDIT_EXPORT");
        assertThat(PermissionCatalog.codesOf("tenant:write")).contains(
                "TENANT_UPDATE", "TENANT_SUBMIT", "TENANT_LIFECYCLE", "TENANT_DELETE");
    }

    @Test
    void requiredCodesUnionsAliasesAndSupportsLiteralCodes() {
        assertThat(PermissionCatalog.requiredCodes(new String[]{"org:read", "TENANT_READ"}))
                .containsExactlyInAnyOrder("ORG_READ", "TENANT_READ");
        assertThat(PermissionCatalog.requiredCodes(new String[]{"account:read"}))
                .containsExactlyInAnyOrder("ACCOUNT_READ", "ACCOUNT_LOGIN_EVENTS_READ");
    }

    @Test
    void unknownAliasesFailClosedWhileUnknownLiteralsAreKept() {
        assertThat(PermissionCatalog.requiredCodes(new String[]{"unknown:alias"})).isEmpty();
        assertThat(PermissionCatalog.requiredCodes(new String[]{"CUSTOM_CODE"}))
                .containsExactly("CUSTOM_CODE");
        assertThat(PermissionCatalog.requiredCodes(null)).isEmpty();
        assertThat(PermissionCatalog.requiredCodes(new String[]{"", "  "})).isEmpty();
    }

    @Test
    void everyAliasBacksOnlyExistingCatalogueCodes() {
        for (String alias : PermissionCatalog.aliases()) {
            assertThat(PermissionCatalog.codesOf(alias))
                    .as("alias %s", alias)
                    .isNotEmpty();
        }
    }
}
