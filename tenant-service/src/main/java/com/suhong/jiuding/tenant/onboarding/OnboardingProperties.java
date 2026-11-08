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
package com.suhong.jiuding.tenant.onboarding;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Public self-service onboarding configuration ({@code tenancy.onboarding.*}). */
@ConfigurationProperties(prefix = "tenancy.onboarding")
public class OnboardingProperties {

    /** Maximum onboarding submissions per client IP per rolling minute. */
    private int rateLimitPerMinute = 5;
    private String defaultPlan = "PRO";
    private String defaultRegion = "cn-shanghai";

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute <= 0 ? 1 : rateLimitPerMinute;
    }

    public void setRateLimitPerMinute(int rateLimitPerMinute) {
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    public String getDefaultPlan() {
        return defaultPlan;
    }

    public void setDefaultPlan(String defaultPlan) {
        this.defaultPlan = defaultPlan;
    }

    public String getDefaultRegion() {
        return defaultRegion;
    }

    public void setDefaultRegion(String defaultRegion) {
        this.defaultRegion = defaultRegion;
    }
}
