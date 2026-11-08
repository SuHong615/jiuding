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
package com.suhong.jiuding.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.error.GlobalExceptionHandler;
import com.suhong.jiuding.common.security.AuthFilter;
import com.suhong.jiuding.common.security.JwtService;
import com.suhong.jiuding.common.security.SecurityProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

/** Shared web/security plumbing auto-configured into every service. */
@AutoConfiguration
@EnableConfigurationProperties(SecurityProperties.class)
public class TenancyCommonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JwtService jwtService(SecurityProperties properties) {
        return new JwtService(properties.getJwtSecret(),
                Duration.ofMinutes(properties.getTokenTtlMinutes()));
    }

    @Bean
    public FilterRegistrationBean<AuthFilter> authFilter(SecurityProperties properties,
                                                         JwtService jwtService,
                                                         ObjectMapper objectMapper) {
        FilterRegistrationBean<AuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AuthFilter(properties, jwtService, objectMapper));
        registration.setOrder(-100);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("X-Request-Id"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
