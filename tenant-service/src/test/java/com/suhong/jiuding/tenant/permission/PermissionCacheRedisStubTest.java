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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * docs/23 section 5: stubbed Redis path — role keys with 60s TTL, eviction and graceful
 * degradation to memory when Redis raises an error.
 */
class PermissionCacheRedisStubTest {

    private static final Set<String> ROLE = Set.of("CUSTOM_READER");

    @Test
    void redisHitReturnsTheCachedPermissionCodesWithoutTouchingTheDatabase() {
        RbacRepository repository = mock(RbacRepository.class);
        Fixture fixture = new Fixture(repository);
        when(fixture.valueOperations().get("rbac:perm:CUSTOM_READER"))
                .thenReturn("[\"ORG_READ\",\"ACCOUNT_READ\"]");

        assertThat(fixture.cache().permissions(ROLE))
                .containsExactlyInAnyOrder("ORG_READ", "ACCOUNT_READ");
        verifyNoInteractions(repository);
        assertThat(fixture.cache().size()).isZero();
    }

    @Test
    void redisMissLoadsTheRoleAndWritesItWithSixtySecondTtl() {
        RbacRepository repository = mock(RbacRepository.class);
        Fixture fixture = new Fixture(repository);
        when(fixture.valueOperations().get(anyString())).thenReturn(null);
        when(repository.permissionCodesForRole("CUSTOM_READER"))
                .thenReturn(List.of("ORG_READ", "ACCOUNT_READ"));
        when(repository.permissionCodesForRoles(anyCollection()))
                .thenReturn(List.of("ORG_READ", "ACCOUNT_READ"));

        assertThat(fixture.cache().permissions(ROLE))
                .containsExactlyInAnyOrder("ORG_READ", "ACCOUNT_READ");
        verify(fixture.valueOperations()).set("rbac:perm:CUSTOM_READER",
                "[\"ORG_READ\",\"ACCOUNT_READ\"]", Duration.ofMillis(60_000L));

        fixture.cache().evictAll();
        verify(fixture.redisTemplate()).delete(Set.of("rbac:perm:CUSTOM_READER"));
    }

    @Test
    void redisFailureFallsBackToTheMemoryCache() {
        RbacRepository repository = mock(RbacRepository.class);
        when(repository.permissionCodesForRoles(anyCollection()))
                .thenReturn(List.of("ORG_READ"));
        Fixture fixture = new Fixture(repository);
        when(fixture.redisTemplate().opsForValue())
                .thenThrow(new RuntimeException("redis is down"));

        assertThat(fixture.cache().permissions(ROLE)).containsExactly("ORG_READ");
        // Second lookup uses the in-memory fallback populated by the first call.
        assertThat(fixture.cache().permissions(ROLE)).containsExactly("ORG_READ");
        verify(repository).permissionCodesForRoles(anyCollection());
    }

    private static final class Fixture {

        private final StringRedisTemplate redisTemplate;
        private final ValueOperations<String, String> valueOperations;
        private final PermissionCache cache;

        @SuppressWarnings("unchecked")
        Fixture(RbacRepository repository) {
            this.redisTemplate = mock(StringRedisTemplate.class);
            this.valueOperations = mock(ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            this.cache = new PermissionCache(repository, 60_000L, redisTemplate, true);
        }

        StringRedisTemplate redisTemplate() {
            return redisTemplate;
        }

        ValueOperations<String, String> valueOperations() {
            return valueOperations;
        }

        PermissionCache cache() {
            return cache;
        }
    }
}
