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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform-login fallback switch (docs/23 §5).
 *
 * <p>{@code tenancy.auth.preset-fallback-enabled} (default false) keeps the legacy in-memory
 * preset platform users as a fallback when tenant-service platform authentication is
 * unavailable. Production must keep this disabled so platform accounts come from the database
 * only; local development/test profiles enable it for convenience.
 */
@ConfigurationProperties(prefix = "tenancy.auth")
public class AuthFallbackProperties {

    private boolean presetFallbackEnabled = false;

    public boolean isPresetFallbackEnabled() {
        return presetFallbackEnabled;
    }

    public void setPresetFallbackEnabled(boolean presetFallbackEnabled) {
        this.presetFallbackEnabled = presetFallbackEnabled;
    }
}
