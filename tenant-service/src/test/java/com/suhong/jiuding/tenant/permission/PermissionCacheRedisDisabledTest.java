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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** docs/23 section 5: Redis disabled keeps the existing in-memory permission cache working. */
@SpringBootTest
@ActiveProfiles("test")
class PermissionCacheRedisDisabledTest {

    @Autowired
    private PermissionCache permissionCache;
    @Autowired
    private Environment environment;

    @Test
    void redisIsDisabledAndMemoryCacheStillServesPermissions() {
        assertThat(environment.getProperty("tenancy.redis.enabled", Boolean.class, false)).isFalse();

        assertThat(permissionCache.permissions(Set.of("MEMBER")))
                .contains("ORG_READ", "ACCOUNT_READ");
        assertThat(permissionCache.size()).isGreaterThanOrEqualTo(1);

        permissionCache.evictAll();
        assertThat(permissionCache.size()).isZero();
        // After eviction the cache repopulates from the database matrix.
        assertThat(permissionCache.permissions(Set.of("MEMBER"))).contains("ORG_READ");
    }
}
