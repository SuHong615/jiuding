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
package com.suhong.jiuding.common.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private final JwtService jwt = new JwtService("unit-test-secret-key-please-change", Duration.ofMinutes(30));

    @Test
    void issueAndParseRoundTrip() {
        Principal principal = new Principal("u-admin", "admin", null,
                Set.of("PLATFORM_ADMIN"), "JWT");
        String token = jwt.issue(principal);
        assertThat(token).hasSizeGreaterThan(40);

        Principal parsed = jwt.parse(token);
        assertThat(parsed.userId()).isEqualTo("u-admin");
        assertThat(parsed.username()).isEqualTo("admin");
        assertThat(parsed.tenantId()).isNull();
        assertThat(parsed.roles()).contains("PLATFORM_ADMIN");
    }

    @Test
    void tenantClaimIsPreserved() {
        Principal principal = new Principal("u-op", "operator", "t-acme", Set.of("TENANT_ADMIN"), "JWT");
        Principal parsed = jwt.parse(jwt.issue(principal));
        assertThat(parsed.tenantId()).isEqualTo("t-acme");
    }

    @Test
    void rejectsTamperedToken() {
        String token = jwt.issue(new Principal("u", "u", "t1", Set.of("AUDITOR"), "JWT"));
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertThatThrownBy(() -> jwt.parse(tampered))
                .isInstanceOf(com.suhong.jiuding.common.error.BizException.class)
                .hasMessageContaining("invalid signature");
    }

    @Test
    void rejectsExpiredToken() {
        JwtService shortLived = new JwtService("unit-test-secret-key-please-change", Duration.ofSeconds(-10));
        String token = shortLived.issue(new Principal("u", "u", "t1", Set.of("AUDITOR"), "JWT"));
        assertThatThrownBy(() -> shortLived.parse(token)).hasMessageContaining("expired");
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> jwt.parse("not-a-token")).hasMessageContaining("malformed");
        assertThatThrownBy(() -> jwt.parse("")).hasMessageContaining("missing");
    }

    @Test
    void rejectsTooShortSecret() {
        assertThatThrownBy(() -> new JwtService("short", Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
