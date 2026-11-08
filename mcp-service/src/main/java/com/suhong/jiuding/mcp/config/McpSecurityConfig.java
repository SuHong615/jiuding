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
package com.suhong.jiuding.mcp.config;

import com.suhong.jiuding.common.security.JwtService;
import com.suhong.jiuding.common.security.SecurityProperties;
import com.suhong.jiuding.mcp.credentials.ApiKeyService;
import com.suhong.jiuding.mcp.credentials.DbApiKeyAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the personal API-key bridge before the shared JWT AuthFilter (order -100). */
@Configuration
public class McpSecurityConfig {

    @Bean
    public FilterRegistrationBean<DbApiKeyAuthenticationFilter> dbApiKeyAuthenticationFilter(
            ApiKeyService apiKeyService, JwtService jwtService, SecurityProperties securityProperties) {
        FilterRegistrationBean<DbApiKeyAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new DbApiKeyAuthenticationFilter(apiKeyService, jwtService, securityProperties));
        registration.setOrder(DbApiKeyAuthenticationFilter.FILTER_ORDER);
        registration.addUrlPatterns("/*");
        registration.setName("mcpDbApiKeyAuthenticationFilter");
        return registration;
    }
}
