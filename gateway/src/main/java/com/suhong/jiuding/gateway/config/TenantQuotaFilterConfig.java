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
package com.suhong.jiuding.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.gateway.quota.TenantQuotaClient;
import com.suhong.jiuding.gateway.quota.TenantQuotaFilter;
import com.suhong.jiuding.gateway.quota.TenantQuotaLimiter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the tenant quota filter after the shared AuthFilter (order -100) so the
 * authenticated principal's tenant id is available.
 */
@Configuration
public class TenantQuotaFilterConfig {

    @Bean
    public FilterRegistrationBean<TenantQuotaFilter> tenantQuotaFilter(
            TenantQuotaProperties properties, TenantQuotaClient quotaClient,
            TenantQuotaLimiter quotaLimiter, ObjectMapper objectMapper) {
        FilterRegistrationBean<TenantQuotaFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TenantQuotaFilter(properties, quotaClient, quotaLimiter, objectMapper));
        registration.setOrder(TenantQuotaFilter.FILTER_ORDER);
        registration.addUrlPatterns("/*");
        registration.setName("tenantQuotaFilter");
        return registration;
    }
}
