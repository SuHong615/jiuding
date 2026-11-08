package com.suhong.jiuding.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tenant API RPM quota enforcement ({@code tenancy.gateway.tenant-quota.*}, docs/17 §5).
 *
 * <p>The effective limit is fetched from tenant-service
 * {@code GET /internal/tenants/{id}/quota} ({@code apiRpm.limit}), cached for
 * {@code cache-seconds} and enforced with a sliding window keyed by tenant id.
 */
@ConfigurationProperties(prefix = "tenancy.gateway.tenant-quota")
public class TenantQuotaProperties {

    private boolean enabled = true;
    private long cacheSeconds = 60L;
    private long windowSeconds = 60L;
    private int requestTimeoutMs = 2_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getCacheSeconds() {
        return cacheSeconds;
    }

    public void setCacheSeconds(long cacheSeconds) {
        this.cacheSeconds = cacheSeconds;
    }

    public long getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(long windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public int getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(int requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }
}
