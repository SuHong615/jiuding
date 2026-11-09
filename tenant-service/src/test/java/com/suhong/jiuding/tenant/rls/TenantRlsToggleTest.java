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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** docs/23 section 2: RLS is switched off for H2 dev/test and enabled only by configuration. */
@SpringBootTest
@ActiveProfiles("test")
class TenantRlsToggleTest {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private TenantRlsProperties properties;

    @Test
    void h2TestProfileKeepsTheRawDataSource() {
        assertThat(properties.isEnabled()).isFalse();
        assertThat(dataSource).isNotInstanceOf(TenantRlsDataSource.class);
    }

    @Test
    void wrapOnlyInstallsTheWrapperWhenEnabled() {
        DataSource raw = mock(DataSource.class);
        assertThat(TenantRlsConfiguration.wrap(raw, false)).isSameAs(raw);
        DataSource wrapped = TenantRlsConfiguration.wrap(raw, true);
        assertThat(wrapped).isInstanceOf(TenantRlsDataSource.class);
        // Wrapping twice must not stack another layer.
        assertThat(TenantRlsConfiguration.wrap(wrapped, true)).isSameAs(wrapped);
    }
}
