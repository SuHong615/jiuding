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
package com.suhong.jiuding.gateway.oidc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.security.JwtService;
import com.suhong.jiuding.common.security.Principal;
import com.suhong.jiuding.gateway.config.OidcProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * OIDC authorization-code + PKCE flow (docs/16 §2, docs/20 §3).
 *
 * <p>Supports two configuration sources:
 * <ul>
 *   <li>the legacy env-configured provider ({@code tenancy.auth.oidc.*}) — used when it is
 *       complete and no explicit {@code provider} code is requested;</li>
 *   <li>dynamic SSO providers loaded from tenant-service
 *       ({@link SsoProviderClient}) — selected with {@code ?provider=<code>}; the provider
 *       code is bound into the signed state so the callback uses the right issuer/client.</li>
 * </ul>
 *
 * <p>The flow: {@code /authorize} builds a signed stateless state plus an S256 PKCE
 * challenge; {@code /callback} verifies the state, exchanges the code, reads userinfo, maps
 * roles ({@code role-claim} → known roles, otherwise {@code default-role}) and signs the same
 * JWT as the password login. The final step is a 302 to the provider's frontend redirect URL
 * with the token in the URL fragment.
 */
@Service
public class OidcService {

    private static final Logger log = LoggerFactory.getLogger(OidcService.class);
    private static final Set<String> KNOWN_ROLES = Set.of(
            "PLATFORM_ADMIN", "APPROVER", "AUDITOR", "TENANT_ADMIN", "ORG_ADMIN", "MEMBER");

    private final OidcProperties properties;
    private final OidcStateService stateService;
    private final OidcDiscoveryClient discoveryClient;
    private final SsoProviderClient ssoProviderClient;
    private final JwtService jwtService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OidcService(OidcProperties properties, OidcStateService stateService,
                       OidcDiscoveryClient discoveryClient, SsoProviderClient ssoProviderClient,
                       JwtService jwtService, HttpClient httpClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.stateService = stateService;
        this.discoveryClient = discoveryClient;
        this.ssoProviderClient = ssoProviderClient;
        this.jwtService = jwtService;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> status() {
        List<SsoProviderConfig> dynamicProviders = ssoProviderClient.enabled();
        boolean staticUsable = properties.isUsable();
        boolean enabled = staticUsable || !dynamicProviders.isEmpty();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", enabled);
        body.put("providerName", providerName(staticUsable, dynamicProviders));
        if (enabled) {
            body.put("loginUrl", "/api/auth/oidc/authorize");
            body.put("frontendRedirect", frontendRedirect(staticUsable, dynamicProviders));
            body.put("providerCount", dynamicProviders.size());
        }
        return body;
    }

    /** True when at least one provider (legacy or dynamic) is configured. */
    public boolean anyEnabled() {
        return properties.isUsable() || !ssoProviderClient.enabled().isEmpty();
    }

    /** Public provider list for the login page (docs/20 §3): no client secrets. */
    public List<Map<String, Object>> publicProviders() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SsoProviderConfig provider : ssoProviderClient.enabled()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", provider.code());
            item.put("name", provider.name());
            item.put("loginUrl", "/api/auth/oidc/authorize?provider=" + encode(provider.code()));
            result.add(item);
        }
        if (result.isEmpty() && properties.isUsable()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", "default");
            item.put("name", properties.getProviderName());
            item.put("loginUrl", "/api/auth/oidc/authorize");
            result.add(item);
        }
        return result;
    }

    /** Builds the provider authorization URL (state + PKCE S256 challenge). */
    public String buildAuthorizationUrl(String providerCode) {
        RuntimeProvider provider = resolve(providerCode);
        OidcDiscoveryClient.Endpoints endpoints = discoveryClient.endpoints(provider.issuer(),
                provider.authorizationEndpoint(), provider.tokenEndpoint(), provider.userinfoEndpoint());
        OidcStateService.AuthorizationRequest authorization =
                stateService.newAuthorizationRequest(provider.code());
        String separator = endpoints.authorizationEndpoint().contains("?") ? "&" : "?";
        StringBuilder url = new StringBuilder(endpoints.authorizationEndpoint()).append(separator);
        url.append("response_type=code");
        url.append("&client_id=").append(encode(provider.clientId()));
        url.append("&redirect_uri=").append(encode(provider.redirectUri()));
        url.append("&scope=").append(encode(String.join(" ", provider.scopes())));
        url.append("&state=").append(encode(authorization.state()));
        url.append("&code_challenge=").append(encode(authorization.codeChallenge()));
        url.append("&code_challenge_method=S256");
        return url.toString();
    }

    /**
     * Completes the callback and returns the frontend redirect URL (token in the fragment).
     *
     * @throws OidcException with a safe public {@code error} code on any failure
     */
    public String handleCallback(String code, String state) {
        OidcStateService.PkceSession session = stateService.consume(state)
                .orElseThrow(() -> new OidcException("invalid_state", "OIDC state is invalid or expired"));
        if (code == null || code.isBlank()) {
            throw new OidcException("invalid_request", "OIDC callback is missing the code parameter");
        }
        RuntimeProvider provider = resolve(session.providerCode());
        OidcDiscoveryClient.Endpoints endpoints = discoveryClient.endpoints(provider.issuer(),
                provider.authorizationEndpoint(), provider.tokenEndpoint(), provider.userinfoEndpoint());
        String accessToken = exchangeCode(provider, endpoints, code, session.codeVerifier());
        Map<String, Object> claims = fetchUserInfo(endpoints, accessToken);
        Principal principal = toPrincipal(provider, claims);
        String token = jwtService.issue(principal);
        StringBuilder redirect = new StringBuilder(provider.frontendRedirect());
        redirect.append("#token=").append(encode(token));
        redirect.append("&username=").append(encode(principal.username() == null ? "" : principal.username()));
        return redirect.toString();
    }

    /** Redirect target for failures: only a stable error code is exposed (no details). */
    public String errorRedirect(String errorCode) {
        return frontendRedirectForCode(errorCode) + "#error=" + encode(errorCode);
    }

    private String frontendRedirectForCode(String errorCode) {
        if (properties.getFrontendRedirect() != null && !properties.getFrontendRedirect().isBlank()) {
            return properties.getFrontendRedirect();
        }
        List<SsoProviderConfig> providers = ssoProviderClient.enabled();
        if (!providers.isEmpty() && providers.get(0).frontendRedirect() != null) {
            return providers.get(0).frontendRedirect();
        }
        return "/";
    }

    /**
     * Resolves the runtime provider for a request.
     *
     * @param providerCode explicit {@code ?provider=} value; may be blank
     */
    private RuntimeProvider resolve(String providerCode) {
        if (providerCode != null && !providerCode.isBlank()) {
            SsoProviderConfig config = ssoProviderClient.find(providerCode)
                    .orElseThrow(() -> new OidcException("unknown_provider",
                            "unknown SSO provider: " + providerCode));
            return RuntimeProvider.from(config);
        }
        if (properties.isUsable()) {
            return RuntimeProvider.from(properties);
        }
        List<SsoProviderConfig> providers = ssoProviderClient.enabled();
        if (providers.size() == 1) {
            return RuntimeProvider.from(providers.get(0));
        }
        if (providers.size() > 1) {
            throw new OidcException("provider_required", "provider parameter is required");
        }
        throw new OidcException("not_enabled", "OIDC is not enabled");
    }

    private String providerName(boolean staticUsable, List<SsoProviderConfig> providers) {
        if (staticUsable) {
            return properties.getProviderName();
        }
        if (!providers.isEmpty() && providers.get(0).name() != null && !providers.get(0).name().isBlank()) {
            return providers.get(0).name();
        }
        return properties.getProviderName();
    }

    private String frontendRedirect(boolean staticUsable, List<SsoProviderConfig> providers) {
        if (staticUsable) {
            return properties.getFrontendRedirect();
        }
        if (!providers.isEmpty()) {
            return providers.get(0).frontendRedirect();
        }
        return properties.getFrontendRedirect();
    }

    private String exchangeCode(RuntimeProvider provider, OidcDiscoveryClient.Endpoints endpoints,
                                String code, String codeVerifier) {
        String form = "grant_type=authorization_code"
                + "&code=" + encode(code)
                + "&redirect_uri=" + encode(provider.redirectUri())
                + "&client_id=" + encode(provider.clientId())
                + "&client_secret=" + encode(provider.clientSecret())
                + "&code_verifier=" + encode(codeVerifier);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoints.tokenEndpoint()))
                    .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.warn("OIDC token endpoint returned HTTP {}", response.statusCode());
                throw new OidcException("oidc_token_failed", "OIDC token exchange failed");
            }
            JsonNode root = objectMapper.readTree(response.body());
            String accessToken = root == null ? null : root.path("access_token").asText(null);
            if (accessToken == null || accessToken.isBlank()) {
                throw new OidcException("oidc_token_failed", "OIDC token response has no access_token");
            }
            return accessToken;
        } catch (OidcException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OidcException("oidc_token_failed", "OIDC token exchange was interrupted", ex);
        } catch (Exception ex) {
            log.warn("OIDC token exchange failed: {}", ex.toString());
            throw new OidcException("oidc_token_failed", "OIDC token exchange failed", ex);
        }
    }

    private Map<String, Object> fetchUserInfo(OidcDiscoveryClient.Endpoints endpoints, String accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoints.userinfoEndpoint()))
                    .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.warn("OIDC userinfo endpoint returned HTTP {}", response.statusCode());
                throw new OidcException("oidc_userinfo_failed", "OIDC userinfo request failed");
            }
            return objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {
            });
        } catch (OidcException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OidcException("oidc_userinfo_failed", "OIDC userinfo request was interrupted", ex);
        } catch (Exception ex) {
            log.warn("OIDC userinfo request failed: {}", ex.toString());
            throw new OidcException("oidc_userinfo_failed", "OIDC userinfo request failed", ex);
        }
    }

    private Principal toPrincipal(RuntimeProvider provider, Map<String, Object> claims) {
        String userId = firstText(claims, "sub", "preferred_username", "email");
        if (userId == null) {
            throw new OidcException("oidc_userinfo_failed", "OIDC userinfo has no subject");
        }
        String username = firstText(claims, "name", "preferred_username", "email");
        if (username == null) {
            username = userId;
        }
        Set<String> roles = mapRoles(provider, claims);
        return new Principal(userId, username, null, roles, "JWT", null);
    }

    private Set<String> mapRoles(RuntimeProvider provider, Map<String, Object> claims) {
        Set<String> mapped = new LinkedHashSet<>();
        Object raw = claims.get(provider.roleClaim());
        for (String candidate : roleValues(raw)) {
            String normalized = candidate.trim().toUpperCase(Locale.ROOT);
            if (KNOWN_ROLES.contains(normalized)) {
                mapped.add(normalized);
            }
        }
        if (mapped.isEmpty() && provider.defaultRole() != null && !provider.defaultRole().isBlank()) {
            mapped.add(provider.defaultRole().trim().toUpperCase(Locale.ROOT));
        }
        return mapped;
    }

    private List<String> roleValues(Object raw) {
        List<String> values = new ArrayList<>();
        if (raw == null) {
            return values;
        }
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null) {
                    values.add(String.valueOf(item));
                }
            }
            return values;
        }
        if (raw.getClass().isArray()) {
            for (Object item : (Object[]) raw) {
                if (item != null) {
                    values.add(String.valueOf(item));
                }
            }
            return values;
        }
        String text = String.valueOf(raw);
        if (text.contains(" ")) {
            for (String part : text.split("\\s+")) {
                values.add(part);
            }
        } else if (text.contains(",")) {
            for (String part : text.split(",")) {
                values.add(part);
            }
        } else if (!text.isBlank()) {
            values.add(text);
        }
        return values;
    }

    private static String firstText(Map<String, Object> claims, String... keys) {
        for (String key : keys) {
            Object value = claims.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** Effective provider settings used by one authorize/callback round-trip. */
    private record RuntimeProvider(String code,
                                   String issuer,
                                   String clientId,
                                   String clientSecret,
                                   String redirectUri,
                                   List<String> scopes,
                                   String frontendRedirect,
                                   String roleClaim,
                                   String defaultRole,
                                   String authorizationEndpoint,
                                   String tokenEndpoint,
                                   String userinfoEndpoint) {

        static RuntimeProvider from(OidcProperties properties) {
            return new RuntimeProvider(null,
                    properties.getIssuer(),
                    properties.getClientId(),
                    properties.getClientSecret(),
                    properties.getRedirectUri(),
                    splitScopes(properties.getScopes()),
                    properties.getFrontendRedirect(),
                    properties.getRoleClaim(),
                    properties.getDefaultRole(),
                    properties.getAuthorizationEndpoint(),
                    properties.getTokenEndpoint(),
                    properties.getUserinfoEndpoint());
        }

        static RuntimeProvider from(SsoProviderConfig config) {
            return new RuntimeProvider(
                    config.code(),
                    config.issuer(),
                    config.clientId(),
                    config.clientSecret(),
                    config.redirectUri(),
                    splitScopes(config.scopes()),
                    config.frontendRedirect(),
                    config.roleClaim() == null ? "roles" : config.roleClaim(),
                    config.defaultRole() == null ? "AUDITOR" : config.defaultRole(),
                    null, null, null);
        }

        private static List<String> splitScopes(String scopes) {
            List<String> result = new ArrayList<>();
            if (scopes == null) {
                return result;
            }
            for (String scope : scopes.split("[,\\s]+")) {
                if (!scope.isBlank()) {
                    result.add(scope.trim());
                }
            }
            return result;
        }
    }
}
