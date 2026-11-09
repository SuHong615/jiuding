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
package com.suhong.jiuding.tenant.rls;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

/**
 * Installs {@link TenantRlsDataSource} around the application DataSource when
 * {@code tenancy.rls.enabled=true} (Docker / k8s). H2 dev and tests keep the raw DataSource.
 */
@Configuration
public class TenantRlsConfiguration {

    @Bean
    public static BeanPostProcessor tenantRlsDataSourcePostProcessor(Environment environment) {
        return new TenantRlsDataSourcePostProcessor(environment);
    }

    /** Exposed for tests: returns the wrapper only when RLS is enabled. */
    public static DataSource wrap(DataSource dataSource, boolean enabled) {
        if (!enabled || dataSource == null || dataSource instanceof TenantRlsDataSource) {
            return dataSource;
        }
        return new TenantRlsDataSource(dataSource);
    }

    private static final class TenantRlsDataSourcePostProcessor implements BeanPostProcessor {

        private final Environment environment;

        private TenantRlsDataSourcePostProcessor(Environment environment) {
            this.environment = environment;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof DataSource dataSource) {
                boolean enabled = environment.getProperty("tenancy.rls.enabled",
                        Boolean.class, Boolean.FALSE);
                return wrap(dataSource, enabled);
            }
            return bean;
        }
    }
}
