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

import com.suhong.jiuding.gateway.auth.LoginRateLimiter;
import com.suhong.jiuding.gateway.support.FreePorts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Redis is enabled but unreachable: login limiting must keep working through the in-memory
 * degradation path (docs/23 §5) — the 11th attempt is still rejected with 429/42900.
 */
@SpringBootTest(properties = {
        "tenancy.redis.enabled=true",
        "tenancy.auth.preset-fallback-enabled=true"
})
@AutoConfigureMockMvc
class RedisRateLimitDegradationTest {

    private static final int CLOSED_PORT = FreePorts.closedPort();

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("tenancy.redis.url", () -> "redis://127.0.0.1:" + CLOSED_PORT);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LoginRateLimiter loginRateLimiter;

    @BeforeEach
    void reset() {
        loginRateLimiter.reset();
    }

    @Test
    void redisOutageDegradesLoginLimiterToMemory() throws Exception {
        for (int attempt = 1; attempt <= 10; attempt++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"admin\",\"password\":\"WrongPassword@1\"}"))
                    .andExpect(status().isUnauthorized());
        }
        MvcResult blocked = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"WrongPassword@1\"}"))
                .andExpect(status().isTooManyRequests())
                .andReturn();
        String body = blocked.getResponse().getContentAsString();
        assertThat(body).contains("\"code\":42900").contains(LoginRateLimiter.TOO_MANY_ATTEMPTS);
    }
}
