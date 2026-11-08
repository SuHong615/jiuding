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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthFilterTest {

    private final SecurityProperties properties = new SecurityProperties();
    private final JwtService jwt = new JwtService("unit-test-secret-key-please-change", Duration.ofMinutes(30));
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AuthFilter filter = new AuthFilter(properties, jwt, mapper);

    @AfterEach
    void cleanup() {
        AuthContext.clear();
        TenantContext.clear();
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void bearerTokenBindsPrincipalAndTenant() throws Exception {
        properties.setApiKeys(List.of("dev-mcp-key"));
        properties.setInternalToken("dev-internal-token");
        String token = jwt.issue(new Principal("u-op", "operator", "t-acme", Set.of("TENANT_ADMIN"), "JWT"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tenants");
        request.addHeader(Headers.AUTHORIZATION, "Bearer " + token);
        MockHttpServletResponse response = run(request);

        assertThat(response.getStatus()).isEqualTo(200);
        // AuthContext/TenantContext are cleared after the chain; assert via request-scoped capture instead.
        assertThat(response.getHeader(Headers.REQUEST_ID)).isNotBlank();
    }

    @Test
    void missingCredentialsReturns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tenants");
        MockHttpServletResponse response = run(request);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("authentication required");
    }

    @Test
    void validApiKeyIsAccepted() throws Exception {
        properties.setApiKeys(List.of("dev-mcp-key"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader(Headers.API_KEY, "dev-mcp-key");
        MockHttpServletResponse response = run(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void invalidApiKeyIsRejected() throws Exception {
        properties.setApiKeys(List.of("dev-mcp-key"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader(Headers.API_KEY, "wrong");
        MockHttpServletResponse response = run(request);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void internalPathAcceptsInternalToken() throws Exception {
        properties.setInternalToken("dev-internal-token");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/tenants/t1/status");
        request.addHeader(Headers.INTERNAL_TOKEN, "dev-internal-token");
        MockHttpServletResponse response = run(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void internalPathRejectsWrongToken() throws Exception {
        properties.setInternalToken("dev-internal-token");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/tenants/t1/status");
        request.addHeader(Headers.INTERNAL_TOKEN, "bad");
        MockHttpServletResponse response = run(request);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void internalPathWithoutTokenFallsBackToNormalAuth() throws Exception {
        properties.setInternalToken("dev-internal-token");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/tenants/t1/status");
        MockHttpServletResponse response = run(request);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void tenantHeaderMismatchIsForbidden() throws Exception {
        String token = jwt.issue(new Principal("u-op", "operator", "t-acme", Set.of("TENANT_ADMIN"), "JWT"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tenants");
        request.addHeader(Headers.AUTHORIZATION, "Bearer " + token);
        request.addHeader(Headers.TENANT_ID, "t-other");
        MockHttpServletResponse response = run(request);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("does not match");
    }

    @Test
    void platformAdminCanCrossTenants() throws Exception {
        String token = jwt.issue(new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tenants");
        request.addHeader(Headers.AUTHORIZATION, "Bearer " + token);
        request.addHeader(Headers.TENANT_ID, "t-other");
        MockHttpServletResponse response = run(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void chainCanReadTenantScope() throws Exception {
        String token = jwt.issue(new Principal("u-op", "operator", "t-acme", Set.of("TENANT_ADMIN"), "JWT"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tenants");
        request.addHeader(Headers.AUTHORIZATION, "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] captured = new String[1];
        filter.doFilter(request, response, (req, res) -> captured[0] = TenantContext.tenantId());
        assertThat(captured[0]).isEqualTo("t-acme");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void whitelistedPathSkipsAuth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse response = run(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void tenantContextFailsClosedWhenEmpty() {
        TenantContext.clear();
        assertThatThrownBy(TenantContext::require).isInstanceOf(BizException.class);
    }
}
