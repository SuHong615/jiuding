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
package com.suhong.jiuding.tenant.bootstrap;

import com.suhong.jiuding.tenant.platformuser.PlatformUserRepository;
import com.suhong.jiuding.tenant.platformuser.PlatformUserRow;
import com.suhong.jiuding.tenant.platformuser.PlatformUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** docs/23 section 5: env driven platform administrator bootstrap, no hard-coded credentials. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformUserBootstrapTest {

    private static final String PASSWORD = "BootstrapPassw0rd";

    @Autowired
    private PlatformUserService userService;
    @Autowired
    private PlatformUserRepository repository;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM platform_users");
        jdbc.update("DELETE FROM tenant_audits WHERE tenant_id = 'platform'"
                + " AND action = 'PLATFORM_USER_BOOTSTRAPPED'");
    }

    @Test
    void createsTheFirstPlatformUserOnlyWhenTableIsEmptyAndPasswordIsPresent() {
        PlatformUserBootstrap bootstrap = new PlatformUserBootstrap(userService, repository,
                "platform-admin", PASSWORD, "PLATFORM_ADMIN,AUDITOR");
        assertThat(bootstrap.bootstrap()).isTrue();

        assertThat(repository.count()).isEqualTo(1L);
        PlatformUserRow row = repository.findByUsername("platform-admin").orElseThrow();
        assertThat(row.roles()).isEqualTo("PLATFORM_ADMIN,AUDITOR");
        assertThat(row.status()).isEqualTo("ACTIVE");
        assertThat(row.passwordHash()).contains(":").doesNotContain(PASSWORD);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audits"
                        + " WHERE tenant_id = 'platform' AND action = 'PLATFORM_USER_BOOTSTRAPPED'",
                Long.class)).isEqualTo(1L);

        // A second startup must not overwrite or duplicate the existing user.
        PlatformUserBootstrap second = new PlatformUserBootstrap(userService, repository,
                "other-admin", PASSWORD, "PLATFORM_ADMIN");
        assertThat(second.bootstrap()).isFalse();
        assertThat(repository.count()).isEqualTo(1L);
        assertThat(repository.findByUsername("other-admin")).isEmpty();
    }

    @Test
    void skipsWithoutUsernameOrPasswordAndDefaultsRoles() {
        assertThat(new PlatformUserBootstrap(userService, repository, "", PASSWORD, null)
                .bootstrap()).isFalse();
        assertThat(new PlatformUserBootstrap(userService, repository, "platform-admin", "", null)
                .bootstrap()).isFalse();
        assertThat(new PlatformUserBootstrap(userService, repository, "platform-admin", "   ", null)
                .bootstrap()).isFalse();
        assertThat(repository.count()).isZero();

        assertThat(PlatformUserBootstrap.parseRoles(null)).containsExactly("PLATFORM_ADMIN");
        assertThat(PlatformUserBootstrap.parseRoles(" , ")).containsExactly("PLATFORM_ADMIN");
        assertThat(PlatformUserBootstrap.parseRoles("platform_admin, auditor ")).containsExactly(
                "PLATFORM_ADMIN", "AUDITOR");

        PlatformUserBootstrap bootstrap = new PlatformUserBootstrap(userService, repository,
                "platform-admin", PASSWORD, null);
        assertThat(bootstrap.bootstrap()).isTrue();
        assertThat(repository.findByUsername("platform-admin").orElseThrow().roles())
                .isEqualTo("PLATFORM_ADMIN");
    }
}
