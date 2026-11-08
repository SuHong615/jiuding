package com.suhong.jiuding.gateway.quota;

import com.suhong.jiuding.gateway.config.TenantQuotaProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory sliding-window request counter per tenant id (docs/17 §5).
 * Window length {@code tenancy.gateway.tenant-quota.window-seconds} (60s by default).
 */
@Component
public class TenantQuotaLimiter {

    private static final int PURGE_EVERY_CALLS = 1024;

    private final TenantQuotaProperties properties;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private long calls;

    public TenantQuotaLimiter(TenantQuotaProperties properties) {
        this.properties = properties;
    }

    /**
     * Records one request for the tenant and reports whether it is within the limit.
     *
     * @param limit positive API RPM limit
     * @return {@code false} when the sliding window is already full
     */
    public synchronized boolean tryAcquire(String tenantId, long limit) {
        long windowMs = Math.max(1L, properties.getWindowSeconds()) * 1000L;
        long now = System.currentTimeMillis();
        Window window = windows.computeIfAbsent(tenantId, ignored -> new Window());
        purgeOlderThan(window, now - windowMs);
        boolean allowed = window.timestamps.size() < limit;
        if (allowed) {
            window.timestamps.addLast(now);
        }
        window.lastSeen = now;
        if ((++calls % PURGE_EVERY_CALLS) == 0) {
            purgeStaleWindows(now - windowMs * 2);
        }
        return allowed;
    }

    /** Visible for tests. */
    public synchronized void reset() {
        windows.clear();
        calls = 0;
    }

    private static void purgeOlderThan(Window window, long cutoff) {
        while (!window.timestamps.isEmpty() && window.timestamps.peekFirst() <= cutoff) {
            window.timestamps.pollFirst();
        }
    }

    private void purgeStaleWindows(long cutoff) {
        Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            Window window = iterator.next().getValue();
            purgeOlderThan(window, cutoff);
            if (window.timestamps.isEmpty() && window.lastSeen <= cutoff) {
                iterator.remove();
            }
        }
    }

    private static final class Window {
        private final Deque<Long> timestamps = new ArrayDeque<>();
        private long lastSeen;
    }
}
