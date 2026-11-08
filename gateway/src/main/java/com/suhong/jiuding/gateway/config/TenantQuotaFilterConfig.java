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
