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
package com.suhong.jiuding.gateway.proxy;

import com.suhong.jiuding.gateway.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Prefix routing (docs/02 §0):
 * <ul>
 *   <li>{@code /api/tenants/**} → tenant-service</li>
 *   <li>{@code /api/approvals/**} → approval-service</li>
 *   <li>{@code /api/skills/**}, {@code /api/agents/**} → agent-service</li>
 *   <li>{@code /mcp/**} → mcp-service (streaming SSE enabled)</li>
 * </ul>
 * {@code /api/auth/**} and {@code /api/platform/**} are handled locally.
 *
 * <p>Both the bare prefix and the {@code /**} form are mapped so that
 * {@code POST /api/tenants} and {@code GET /mcp} are covered regardless of the
 * path-matching implementation.
 */
@RestController
public class ProxyController {

    private final GatewayProxyService proxyService;
    private final GatewayProperties properties;

    public ProxyController(GatewayProxyService proxyService, GatewayProperties properties) {
        this.proxyService = proxyService;
        this.properties = properties;
    }

    @RequestMapping({"/api/tenants", "/api/tenants/**"})
    public void proxyTenantService(HttpServletRequest request, HttpServletResponse response) throws IOException {
        proxyService.forward(request, response, new ServiceRoute(
                "tenant-service", properties.getTenantServiceUrl(), properties.getRequestTimeoutMs()));
    }

    @RequestMapping({"/api/approvals", "/api/approvals/**"})
    public void proxyApprovalService(HttpServletRequest request, HttpServletResponse response) throws IOException {
        proxyService.forward(request, response, new ServiceRoute(
                "approval-service", properties.getApprovalServiceUrl(), properties.getRequestTimeoutMs()));
    }

    @RequestMapping({"/api/skills", "/api/skills/**", "/api/agents", "/api/agents/**"})
    public void proxyAgentService(HttpServletRequest request, HttpServletResponse response) throws IOException {
        proxyService.forward(request, response, new ServiceRoute(
                "agent-service", properties.getAgentServiceUrl(), properties.getRequestTimeoutMs()));
    }

    @RequestMapping({"/mcp", "/mcp/**"})
    public void proxyMcpService(HttpServletRequest request, HttpServletResponse response) throws IOException {
        proxyService.forward(request, response, new ServiceRoute(
                "mcp-service", properties.getMcpServiceUrl(), properties.getMcpTimeoutMs()));
    }

    /**
     * v5 MCP console API (docs/15 §4.2): personal keys / audits / external MCP servers live
     * in mcp-service behind the normal REST timeout.
     */
    @RequestMapping({"/api/mcp", "/api/mcp/**"})
    public void proxyMcpConsole(HttpServletRequest request, HttpServletResponse response) throws IOException {
        proxyService.forward(request, response, new ServiceRoute(
                "mcp-service", properties.getMcpServiceUrl(), properties.getRequestTimeoutMs()));
    }

    /**
     * v5 tenant identity/RBAC APIs (docs/15 §7): {@code /api/me/**} and the platform
     * role/permission catalogue are served by tenant-service. Kept as an exact-prefix route
     * so the local {@code /api/platform/overview|health} handlers are not shadowed.
     */
    @RequestMapping({"/api/me", "/api/me/**", "/api/platform/roles", "/api/platform/roles/**",
            "/api/platform/permissions"})
    public void proxyTenantIdentity(HttpServletRequest request, HttpServletResponse response) throws IOException {
        proxyService.forward(request, response, new ServiceRoute(
                "tenant-service", properties.getTenantServiceUrl(), properties.getRequestTimeoutMs()));
    }

    /** v5 public self-service onboarding API (docs/15 §6): unauthenticated, whitelisted. */
    @RequestMapping({"/api/public", "/api/public/**"})
    public void proxyTenantPublic(HttpServletRequest request, HttpServletResponse response) throws IOException {
        proxyService.forward(request, response, new ServiceRoute(
                "tenant-service", properties.getTenantServiceUrl(), properties.getRequestTimeoutMs()));
    }

    /**
     * SCIM 2.0 endpoints (docs/16 §7.1). They authenticate with a tenant-bound
     * {@code Authorization: Bearer scim_...} token which only tenant-service can validate, so
     * {@code /scim/**} is whitelisted: the gateway neither rejects nor parses the request and
     * simply relays it (Authorization included) to tenant-service.
     */
    @RequestMapping({"/scim", "/scim/**"})
    public void proxyScim(HttpServletRequest request, HttpServletResponse response) throws IOException {
        proxyService.forward(request, response, new ServiceRoute(
                "tenant-service", properties.getTenantServiceUrl(), properties.getRequestTimeoutMs()));
    }

    /** Platform SCIM token management (JWT, platform-admin enforced by tenant-service). */
    @RequestMapping({"/api/platform/scim-tokens", "/api/platform/scim-tokens/**"})
    public void proxyScimTokens(HttpServletRequest request, HttpServletResponse response) throws IOException {
        proxyService.forward(request, response, new ServiceRoute(
                "tenant-service", properties.getTenantServiceUrl(), properties.getRequestTimeoutMs()));
    }

    /** v10 platform account management (docs/20 §3): tenant-service enforces platform admin. */
    @RequestMapping({"/api/platform/users", "/api/platform/users/**"})
    public void proxyPlatformUsers(HttpServletRequest request, HttpServletResponse response) throws IOException {
        proxyService.forward(request, response, new ServiceRoute(
                "tenant-service", properties.getTenantServiceUrl(), properties.getRequestTimeoutMs()));
    }

    /** v10 SSO provider management (docs/20 §3): tenant-service enforces platform admin. */
    @RequestMapping({"/api/platform/sso-providers", "/api/platform/sso-providers/**"})
    public void proxySsoProviders(HttpServletRequest request, HttpServletResponse response) throws IOException {
        proxyService.forward(request, response, new ServiceRoute(
                "tenant-service", properties.getTenantServiceUrl(), properties.getRequestTimeoutMs()));
    }

    /** v10 tenant lifecycle workflow view (docs/20 §4.5): read-only aggregation in tenant-service. */
    @RequestMapping({"/api/workflows", "/api/workflows/**"})
    public void proxyWorkflows(HttpServletRequest request, HttpServletResponse response) throws IOException {
        proxyService.forward(request, response, new ServiceRoute(
                "tenant-service", properties.getTenantServiceUrl(), properties.getRequestTimeoutMs()));
    }

    /** v11 platform audit log (JWT, platform-admin enforced by tenant-service). */
    @RequestMapping({"/api/platform/audits", "/api/platform/audits/**"})
    public void proxyPlatformAudits(HttpServletRequest request, HttpServletResponse response) throws IOException {
        proxyService.forward(request, response, new ServiceRoute(
                "tenant-service", properties.getTenantServiceUrl(), properties.getRequestTimeoutMs()));
    }
}
