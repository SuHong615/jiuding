package com.suhong.jiuding.gateway.quota;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.security.JwtService;
import com.suhong.jiuding.common.security.Principal;
import com.suhong.jiuding.gateway.support.StubServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tenant API-RPM quota (docs/17 §5): limit fetched from
 * {@code GET /internal/tenants/{id}/quota}, 60s cache, sliding window per tenant id,
 * 429/42901 over the limit, unlimited degradation on quota-endpoint failure and no counting
 * for platform JWTs (no {@code tid}).
 */
@SpringBootTest(properties = {
        "tenancy.gateway.tenant-quota.enabled=true",
        "tenancy.gateway.tenant-quota.cache-seconds=60",
        "tenancy.gateway.tenant-quota.window-seconds=60"
})
@AutoConfigureMockMvc
class TenantQuotaFilterTest {

    /** tenantId -> quota mode: numeric limit, "null", "401", "500". */
    private static final Map<String, String> QUOTA_MODES = new ConcurrentHashMap<>();

    private static final StubServer TENANT = new StubServer((request, exchange) -> {
        String path = request.path();
        if (path.startsWith("/internal/tenants/") && path.endsWith("/quota")) {
            String tenantId = path.substring("/internal/tenants/".length(),
                    path.length() - "/quota".length());
            String mode = QUOTA_MODES.getOrDefault(tenantId, "2");
            if ("401".equals(mode)) {
                StubServer.respond(exchange, 401, "application/json;charset=UTF-8",
                        "{\"code\":40100,\"message\":\"invalid internal token\"}");
            } else if ("500".equals(mode)) {
                StubServer.respond(exchange, 500, "application/json;charset=UTF-8",
                        "{\"code\":50000,\"message\":\"boom\"}");
            } else if ("null".equals(mode)) {
                StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                        "{\"code\":0,\"message\":\"ok\",\"data\":{\"tenantId\":\"" + tenantId
                                + "\",\"plan\":\"PRO\",\"apiRpm\":{\"limit\":null}}}");
            } else {
                StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                        "{\"code\":0,\"message\":\"ok\",\"data\":{\"tenantId\":\"" + tenantId
                                + "\",\"plan\":\"PRO\",\"apiRpm\":{\"limit\":" + mode + "}}}");
            }
            return;
        }
        StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                "{\"code\":0,\"message\":\"ok\",\"data\":{\"routedBy\":\"tenant-service\"}}");
    });

    static {
        QUOTA_MODES.put("t-acme", "2");
        QUOTA_MODES.put("t-beta", "2");
        QUOTA_MODES.put("t-null", "null");
        QUOTA_MODES.put("t-degrade401", "401");
        QUOTA_MODES.put("t-degrade500", "500");
        QUOTA_MODES.put("t-cache", "100");
    }

    @DynamicPropertySource
    static void downstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("tenancy.gateway.tenant-service-url", TENANT::baseUrl);
        registry.add("tenancy.gateway.approval-service-url", TENANT::baseUrl);
        registry.add("tenancy.gateway.agent-service-url", TENANT::baseUrl);
        registry.add("tenancy.gateway.mcp-service-url", TENANT::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        TENANT.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private TenantQuotaLimiter quotaLimiter;

    @Autowired
    private TenantQuotaClient quotaClient;

    @BeforeEach
    void resetQuotaState() {
        quotaLimiter.reset();
        quotaClient.clearCache();
        TENANT.reset();
    }

    @Test
    void thirdRequestIsRejectedWith429AndQuotaErrorCode() throws Exception {
        String token = tenantToken("t-acme");
        assertThat(callTenant(token).getResponse().getStatus()).isEqualTo(200);
        assertThat(callTenant(token).getResponse().getStatus()).isEqualTo(200);

        MvcResult blocked = callTenant(token);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        var body = objectMapper.readTree(blocked.getResponse().getContentAsString());
        assertThat(body.path("code").asInt()).isEqualTo(42901);
        assertThat(body.path("message").asText()).isEqualTo("tenant api quota exceeded");

        // Only the two admitted requests reached tenant-service.
        long proxied = TENANT.requests().stream()
                .filter(request -> "/api/tenants".equals(request.path()))
                .count();
        assertThat(proxied).isEqualTo(2);

        // Quota lookup used the internal token.
        StubServer.Recorded quotaCall = TENANT.requests().stream()
                .filter(request -> request.path().contains("/internal/tenants/"))
                .findFirst()
                .orElse(null);
        assertThat(quotaCall).isNotNull();
        assertThat(quotaCall.header("x-internal-token")).isEqualTo("dev-internal-token");
    }

    @Test
    void differentTenantsHaveIndependentWindows() throws Exception {
        String acme = tenantToken("t-acme");
        for (int i = 0; i < 3; i++) {
            callTenant(acme);
        }
        assertThat(callTenant(acme).getResponse().getStatus()).isEqualTo(429);

        MvcResult other = callTenant(tenantToken("t-beta"));
        assertThat(other.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void nullLimitMeansUnlimited() throws Exception {
        String token = tenantToken("t-null");
        for (int i = 1; i <= 5; i++) {
            assertThat(callTenant(token).getResponse().getStatus()).isEqualTo(200);
        }
    }

    @Test
    void platformJwtWithoutTenantScopeIsNotCounted() throws Exception {
        String admin = jwtService.issue(new Principal("u-admin", "admin", null,
                Set.of("PLATFORM_ADMIN"), "JWT"));
        long quotaCallsBefore = quotaCallCount();
        for (int i = 1; i <= 5; i++) {
            assertThat(callTenant(admin).getResponse().getStatus()).isEqualTo(200);
        }
        assertThat(quotaCallCount()).isEqualTo(quotaCallsBefore);
    }

    @Test
    void quotaEndpoint401DegradesToUnlimited() throws Exception {
        String token = tenantToken("t-degrade401");
        for (int i = 1; i <= 5; i++) {
            assertThat(callTenant(token).getResponse().getStatus()).isEqualTo(200);
        }
    }

    @Test
    void quotaEndpointUnavailableDegradesToUnlimited() throws Exception {
        String token = tenantToken("t-degrade500");
        for (int i = 1; i <= 5; i++) {
            assertThat(callTenant(token).getResponse().getStatus()).isEqualTo(200);
        }
    }

    @Test
    void quotaLimitIsCachedForSixtySeconds() throws Exception {
        String token = tenantToken("t-cache");
        for (int i = 1; i <= 3; i++) {
            assertThat(callTenant(token).getResponse().getStatus()).isEqualTo(200);
        }
        long quotaCalls = TENANT.requests().stream()
                .filter(request -> request.path().contains("/internal/tenants/t-cache/quota"))
                .count();
        assertThat(quotaCalls).isEqualTo(1);
    }

    @Test
    void localAuthEndpointsAreNotQuotaCounted() throws Exception {
        String token = tenantToken("t-acme");
        long quotaCallsBefore = quotaCallCount();
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantId").value("t-acme"));
        assertThat(quotaCallCount()).isEqualTo(quotaCallsBefore);
    }

    private MvcResult callTenant(String token) throws Exception {
        return mockMvc.perform(get("/api/tenants").header("Authorization", "Bearer " + token))
                .andReturn();
    }

    private long quotaCallCount() {
        return TENANT.requests().stream()
                .filter(request -> request.path().contains("/internal/tenants/"))
                .count();
    }

    private String tenantToken(String tenantId) {
        return jwtService.issue(new Principal("acc-" + tenantId, "alice", tenantId,
                Set.of("TENANT_ADMIN"), "JWT"));
    }
}
