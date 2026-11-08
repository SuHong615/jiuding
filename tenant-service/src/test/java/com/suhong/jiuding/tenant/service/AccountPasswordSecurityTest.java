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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.error.ErrorCode;
import com.suhong.jiuding.tenant.api.AccountCreateRequest;
import com.suhong.jiuding.tenant.api.AccountCreateResult;
import com.suhong.jiuding.tenant.api.AccountView;
import com.suhong.jiuding.tenant.api.ResetPasswordResult;
import com.suhong.jiuding.tenant.repository.AccountRepository;
import com.suhong.jiuding.tenant.repository.AccountRow;
import com.suhong.jiuding.tenant.support.TenantFixtures;
import com.suhong.jiuding.tenant.support.TestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest
@ActiveProfiles("test")
class AccountPasswordSecurityTest {

    @Autowired
    private AccountService accountService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private PasswordHasher passwordHasher;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private String tenantId;

    @BeforeEach
    void setUp() {
        TestSecurity.platformAdmin();
        TenantFixtures.clean(jdbc);
        tenantId = TenantFixtures.createTenant(tenantService, "t-acme", "Acme").id();
    }

    @AfterEach
    void tearDown() {
        TestSecurity.clear();
    }

    @Test
    void generatedTemporaryPasswordIsReturnedOnceAndHashedWithPbkdf2() {
        AccountCreateResult result = accountService.create(tenantId,
                new AccountCreateRequest("Alice", "Alice Chen", "alice@acme.io", null,
                        null, "MEMBER", null));

        assertThat(result.tempPassword()).isNotBlank().hasSize(16);
        assertThat(result.account().username()).isEqualTo("alice");
        assertThat(result.account().mustChangePassword()).isTrue();
        assertThat(result.account().status()).isEqualTo("ACTIVE");

        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT password_hash, password_salt FROM accounts WHERE id = ?",
                result.account().id());
        String hash = (String) stored.get("password_hash");
        String salt = (String) stored.get("password_salt");
        assertThat(hash).isNotBlank().isNotEqualTo(result.tempPassword());
        assertThat(salt).isNotBlank();
        assertThat(passwordHasher.matches(result.tempPassword(), hash, salt)).isTrue();
        // Plain text is never persisted.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE password_hash = ?"
                + " OR password_salt = ?", Long.class, result.tempPassword(), result.tempPassword())).isZero();
    }

    @Test
    void explicitPasswordIsPolicyCheckedAndNeverEchoed() {
        BizException weak = catchThrowableOfType(
                () -> accountService.create(tenantId, new AccountCreateRequest(
                        "weak", "Weak", null, null, null, "MEMBER", "short1")),
                BizException.class);
        assertThat(weak).isNotNull();
        assertThat(weak.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(accountRepository.findByUsername(tenantId, "weak")).isEmpty();

        AccountCreateResult result = accountService.create(tenantId,
                new AccountCreateRequest("bob", "Bob", "bob@acme.io", null, null,
                        "ORG_ADMIN", "Passw0rd123"));
        assertThat(result.tempPassword()).isNull();
        assertThat(result.account().mustChangePassword()).isFalse();
    }

    @Test
    void usernamesAreUniqueIgnoringCase() {
        accountService.create(tenantId, new AccountCreateRequest("Alice", "Alice", null, null,
                null, "MEMBER", "Passw0rd123"));
        BizException duplicate = catchThrowableOfType(
                () -> accountService.create(tenantId, new AccountCreateRequest("ALICE", "Alice 2",
                        null, null, null, "MEMBER", "Passw0rd123")),
                BizException.class);
        assertThat(duplicate).isNotNull();
        assertThat(duplicate.errorCode()).isEqualTo(ErrorCode.CONFLICT);

        // The same username is legal in another tenant (per-tenant unique constraint).
        String otherTenantId = TenantFixtures.createTenant(tenantService, "t-beta", "Beta").id();
        AccountCreateResult other = accountService.create(otherTenantId,
                new AccountCreateRequest("alice", "Alice Beta", null, null, null,
                        "MEMBER", "Passw0rd123"));
        assertThat(other.account().username()).isEqualTo("alice");
    }

    @Test
    void resetPasswordIssuesANewOneTimeTemporaryPassword() {
        AccountCreateResult created = accountService.create(tenantId,
                new AccountCreateRequest("carol", "Carol", null, null, null,
                        "MEMBER", "OldPassw0rd1"));
        String id = created.account().id();
        AccountRow before = accountRepository.findById(tenantId, id).orElseThrow();

        ResetPasswordResult reset = accountService.resetPassword(tenantId, id, null);

        assertThat(reset.tempPassword()).isNotBlank();
        AccountRow after = accountRepository.findById(tenantId, id).orElseThrow();
        assertThat(after.mustChangePassword()).isTrue();
        assertThat(after.passwordHash()).isNotEqualTo(before.passwordHash());
        assertThat(passwordHasher.matches(reset.tempPassword(), after.passwordHash(),
                after.passwordSalt())).isTrue();
        assertThat(passwordHasher.matches("OldPassw0rd1", after.passwordHash(),
                after.passwordSalt())).isFalse();

        // A caller supplied password is validated but never returned.
        ResetPasswordResult explicit = accountService.resetPassword(tenantId, id, "NewPassw0rd2");
        assertThat(explicit.tempPassword()).isNull();
        assertThat(passwordHasher.matches("NewPassw0rd2",
                accountRepository.findById(tenantId, id).orElseThrow().passwordHash(),
                accountRepository.findById(tenantId, id).orElseThrow().passwordSalt())).isTrue();
    }

    @Test
    void tenantAdminCannotGrantTenantAdminRole() {
        TestSecurity.tenantAdmin("t-acme");
        BizException escalation = catchThrowableOfType(
                () -> accountService.create(tenantId, new AccountCreateRequest("chief", "Chief",
                        null, null, null, "TENANT_ADMIN", "Passw0rd123")),
                BizException.class);
        assertThat(escalation).isNotNull();
        assertThat(escalation.errorCode()).isEqualTo(ErrorCode.FORBIDDEN);

        AccountCreateResult orgAdmin = accountService.create(tenantId,
                new AccountCreateRequest("lead", "Lead", null, null, null, "ORG_ADMIN", "Passw0rd123"));
        assertThat(orgAdmin.account().role()).isEqualTo("ORG_ADMIN");

        TestSecurity.platformAdmin();
        AccountCreateResult platformGranted = accountService.create(tenantId,
                new AccountCreateRequest("chief", "Chief", null, null, null, "TENANT_ADMIN", "Passw0rd123"));
        assertThat(platformGranted.account().role()).isEqualTo("TENANT_ADMIN");
    }

    @Test
    void responsesNeverContainPasswordMaterialAndSoftDeleteHidesAccounts() throws Exception {
        AccountCreateResult created = accountService.create(tenantId,
                new AccountCreateRequest("dave", "Dave", null, null, null, "MEMBER", null));
        String json = objectMapper.writeValueAsString(created);
        assertThat(json.toLowerCase()).doesNotContain("passwordhash", "passwordsalt", "password_hash");
        AccountView view = accountService.get(tenantId, created.account().id());
        assertThat(objectMapper.writeValueAsString(view).toLowerCase())
                .doesNotContain("passwordhash", "passwordsalt", "password_hash");

        accountService.delete(tenantId, created.account().id());
        assertThat(accountService.list(tenantId, null, null, null, null, 0, 20).total()).isZero();
        BizException gone = catchThrowableOfType(
                () -> accountService.get(tenantId, created.account().id()), BizException.class);
        assertThat(gone).isNotNull();
        assertThat(gone.errorCode()).isEqualTo(ErrorCode.NOT_FOUND);

        // The DB unique constraint still owns the username even after a soft delete.
        BizException reuse = catchThrowableOfType(
                () -> accountService.create(tenantId, new AccountCreateRequest("dave", "Dave 2",
                        null, null, null, "MEMBER", "Passw0rd123")),
                BizException.class);
        assertThat(reuse).isNotNull();
        assertThat(reuse.errorCode()).isEqualTo(ErrorCode.CONFLICT);
    }
}
