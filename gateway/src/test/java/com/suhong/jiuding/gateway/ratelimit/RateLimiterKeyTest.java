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
package com.suhong.jiuding.gateway.ratelimit;

import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.error.ErrorCode;
import com.suhong.jiuding.gateway.auth.LoginRateLimiter;
import com.suhong.jiuding.gateway.config.LoginRateLimitProperties;
import com.suhong.jiuding.gateway.config.TenantQuotaProperties;
import com.suhong.jiuding.gateway.quota.TenantQuotaLimiter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Login / tenant-quota limiters delegate to a {@link RateLimitStore} with stable logical keys
 * and unchanged thresholds/windows (docs/14 §4, docs/17 §5, docs/23 §5).
 */
class RateLimiterKeyTest {

    @Test
    void loginLimiterUsesIpAndUsernameKeyWithConfiguredThreshold() {
        RecordingStore store = new RecordingStore();
        LoginRateLimitProperties properties = new LoginRateLimitProperties();
        properties.setEnabled(true);
        properties.setMaxRequests(10);
        properties.setWindowSeconds(60);
        LoginRateLimiter limiter = new LoginRateLimiter(properties, store);

        limiter.check("203.0.113.7", "alice");

        assertThat(store.records).hasSize(1);
        assertThat(store.records.get(0).key()).isEqualTo("login:203.0.113.7:alice");
        assertThat(store.records.get(0).limit()).isEqualTo(10);
        assertThat(store.records.get(0).windowMillis()).isEqualTo(60_000L);
    }

    @Test
    void loginLimiterRejectsWhenStoreDenies() {
        RecordingStore store = new RecordingStore();
        store.allowed = false;
        LoginRateLimitProperties properties = new LoginRateLimitProperties();
        properties.setEnabled(true);
        LoginRateLimiter limiter = new LoginRateLimiter(properties, store);

        assertThatThrownBy(() -> limiter.check("203.0.113.7", "alice"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).errorCode())
                        .isEqualTo(ErrorCode.QUOTA_EXCEEDED))
                .hasMessage(LoginRateLimiter.TOO_MANY_ATTEMPTS);
    }

    @Test
    void disabledLoginLimiterDoesNotTouchTheStore() {
        RecordingStore store = new RecordingStore();
        LoginRateLimitProperties properties = new LoginRateLimitProperties();
        properties.setEnabled(false);
        LoginRateLimiter limiter = new LoginRateLimiter(properties, store);

        limiter.check("203.0.113.7", "alice");
        assertThat(store.records).isEmpty();
    }

    @Test
    void tenantQuotaLimiterUsesTenantKeyWithConfiguredWindow() {
        RecordingStore store = new RecordingStore();
        TenantQuotaProperties properties = new TenantQuotaProperties();
        properties.setWindowSeconds(60);
        TenantQuotaLimiter limiter = new TenantQuotaLimiter(properties, store);

        assertThat(limiter.tryAcquire("t-acme", 2)).isTrue();
        assertThat(store.records).hasSize(1);
        assertThat(store.records.get(0).key()).isEqualTo("quota:t-acme");
        assertThat(store.records.get(0).limit()).isEqualTo(2);
        assertThat(store.records.get(0).windowMillis()).isEqualTo(60_000L);
    }

    @Test
    void resetClearsTheStore() {
        RecordingStore store = new RecordingStore();
        TenantQuotaProperties properties = new TenantQuotaProperties();
        TenantQuotaLimiter limiter = new TenantQuotaLimiter(properties, store);

        limiter.reset();
        assertThat(store.resets).isEqualTo(1);
    }

    private static final class RecordingStore implements RateLimitStore {
        private final List<Record> records = new ArrayList<>();
        private boolean allowed = true;
        private int resets;

        @Override
        public boolean tryAcquire(String key, long limit, long windowMillis) {
            records.add(new Record(key, limit, windowMillis));
            return allowed;
        }

        @Override
        public void reset() {
            resets++;
        }

        @Override
        public int trackedKeys() {
            return 0;
        }
    }

    private record Record(String key, long limit, long windowMillis) {
    }
}
