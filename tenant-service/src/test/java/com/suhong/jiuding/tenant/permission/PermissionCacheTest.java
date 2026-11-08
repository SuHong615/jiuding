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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** v9: role -> permission code resolution is cached for 60s and refreshed after expiry. */
@SpringBootTest
@ActiveProfiles("test")
class PermissionCacheTest {

    private static final String ROLE = "CACHE_TEST_ROLE";

    @Autowired
    private RbacRepository repository;

    @AfterEach
    void tearDown() {
        repository.deleteRole(ROLE);
    }

    @Test
    void cachesRolePermissionsUntilTheTtlExpires() throws Exception {
        repository.insertRole(new RoleRow(ROLE, "Cache Test", "TENANT", false, "ACTIVE", null));
        repository.replaceRolePermissions(ROLE, List.of("ORG_READ"));

        PermissionCache cache = new PermissionCache(repository, 80L);
        assertThat(cache.permissions(Set.of(ROLE))).containsExactly("ORG_READ");
        assertThat(cache.size()).isEqualTo(1);

        // The matrix changes but the cached entry is still served.
        repository.replaceRolePermissions(ROLE, List.of("ACCOUNT_READ"));
        assertThat(cache.permissions(Set.of(ROLE))).containsExactly("ORG_READ");

        Thread.sleep(150L);
        assertThat(cache.permissions(Set.of(ROLE))).containsExactly("ACCOUNT_READ");
    }

    @Test
    void evictionForcesTheNextLookupToReadTheDatabase() {
        repository.insertRole(new RoleRow(ROLE, "Cache Test", "TENANT", false, "ACTIVE", null));
        repository.replaceRolePermissions(ROLE, List.of("ORG_READ"));

        PermissionCache cache = new PermissionCache(repository, 60_000L);
        assertThat(cache.permissions(Set.of(ROLE))).containsExactly("ORG_READ");

        repository.replaceRolePermissions(ROLE, List.of("ORG_READ", "ACCOUNT_READ"));
        cache.evictAll();
        assertThat(cache.size()).isZero();
        assertThat(cache.permissions(Set.of(ROLE))).containsExactlyInAnyOrder(
                "ORG_READ", "ACCOUNT_READ");
    }

    @Test
    void emptyRolesHaveNoPermissions() {
        PermissionCache cache = new PermissionCache(repository, 60_000L);
        assertThat(cache.permissions(Set.of())).isEmpty();
        assertThat(cache.permissions(null)).isEmpty();
    }
}
