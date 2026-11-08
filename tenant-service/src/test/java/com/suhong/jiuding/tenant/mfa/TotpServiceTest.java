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
package com.suhong.jiuding.tenant.mfa;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** RFC 6238 TOTP vectors, +/-1 window, base32 and one-time recovery code behaviour. */
class TotpServiceTest {

    private final TotpService totp = new TotpService();
    private static final String RFC_SECRET = Base32.encode(
            "12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void matchesTheRfc6238Sha1VectorForSixDigits() {
        // RFC 6238, SHA-1, T=59s -> 94287082 (8 digits) -> last six digits.
        assertThat(totp.code(RFC_SECRET, 59_000L)).isEqualTo("287082");
    }

    @Test
    void acceptsThePreviousAndNextStepWithinTheWindow() {
        String secret = totp.generateSecret();
        long now = 1_700_000_005_000L; // 5s into a 30s step
        String current = totp.code(secret, now);

        assertThat(totp.verify(secret, current, now)).isTrue();
        assertThat(totp.verify(secret, totp.code(secret, now - 30_000L), now)).isTrue();
        assertThat(totp.verify(secret, totp.code(secret, now + 30_000L), now)).isTrue();
        assertThat(totp.verify(secret, totp.code(secret, now - 60_000L), now)).isFalse();
        assertThat(totp.verify(secret, totp.code(secret, now + 60_000L), now)).isFalse();
        assertThat(totp.verify(secret, "not-a-code", now)).isFalse();
        assertThat(totp.verify("not-base32!", current, now)).isFalse();
    }

    @Test
    void generatesBase32SecretsAndAnOtpauthUri() {
        String secret = totp.generateSecret();
        assertThat(secret).matches("[A-Z2-7]+");
        assertThat(Base32.decode(secret)).hasSize(20);
        assertThat(Base32.encode(Base32.decode(secret))).isEqualTo(secret);

        String url = totp.otpauthUrl("TenancyCenter", "alice@acme", secret);
        assertThat(url).startsWith("otpauth://totp/TenancyCenter:alice%40acme");
        assertThat(url).contains("secret=" + secret)
                .contains("issuer=TenancyCenter")
                .contains("algorithm=SHA1")
                .contains("digits=6")
                .contains("period=30");
    }

    @Test
    void recoveryCodesAreBase32AndSingleUse() {
        List<String> codes = RecoveryCodes.generate();
        assertThat(codes).hasSize(8).doesNotHaveDuplicates();
        assertThat(codes).allMatch(code -> code.matches("[A-Z2-7]{8}"));

        String stored = RecoveryCodes.joinHashes(codes);
        assertThat(stored.split(",")).hasSize(8);

        String remaining = RecoveryCodes.consume(stored, codes.get(0));
        assertThat(remaining).isNotNull();
        assertThat(remaining.split(",")).hasSize(7);
        // The same recovery code cannot be consumed twice.
        assertThat(RecoveryCodes.consume(stored, codes.get(0))).isNotNull();
        assertThat(RecoveryCodes.consume(remaining, codes.get(0))).isNull();
        assertThat(RecoveryCodes.consume(remaining, codes.get(1))).isNotNull();
    }
}
