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
package com.suhong.jiuding.tenant.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Data lifecycle configuration (docs/15 section 8). Bound from the pre-configured
 * {@code tenancy.retention.*} keys in application.yml.
 */
@ConfigurationProperties(prefix = "tenancy.retention")
public class RetentionProperties {

    private boolean autoPurgeEnabled = true;
    private String purgeCron = "0 0 4 * * *";
    private int loginEventDays = 90;
    private int auditDays = 365;
    private int collectJobDays = 90;
    private int softDeletedTenantDays = 180;

    public boolean isAutoPurgeEnabled() {
        return autoPurgeEnabled;
    }

    public void setAutoPurgeEnabled(boolean autoPurgeEnabled) {
        this.autoPurgeEnabled = autoPurgeEnabled;
    }

    public String getPurgeCron() {
        return purgeCron;
    }

    public void setPurgeCron(String purgeCron) {
        this.purgeCron = purgeCron;
    }

    public int getLoginEventDays() {
        return loginEventDays;
    }

    public void setLoginEventDays(int loginEventDays) {
        this.loginEventDays = loginEventDays;
    }

    public int getAuditDays() {
        return auditDays;
    }

    public void setAuditDays(int auditDays) {
        this.auditDays = auditDays;
    }

    public int getCollectJobDays() {
        return collectJobDays;
    }

    public void setCollectJobDays(int collectJobDays) {
        this.collectJobDays = collectJobDays;
    }

    public int getSoftDeletedTenantDays() {
        return softDeletedTenantDays;
    }

    public void setSoftDeletedTenantDays(int softDeletedTenantDays) {
        this.softDeletedTenantDays = softDeletedTenantDays;
    }
}
