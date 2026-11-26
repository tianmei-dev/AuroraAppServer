package com.aurora.appserver.auth.service;

import com.aurora.appserver.auth.config.AuroraAuthProperties;
import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.exception.BusinessException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Counts failed admin password attempts only (sliding window). Never stores passwords.
 */
@Component
public class AdminLoginRateLimiter {

    private final Map<String, ArrayDeque<Long>> windows = new ConcurrentHashMap<>();
    private final AuroraAuthProperties properties;
    private final Clock clock;

    public AdminLoginRateLimiter(AuroraAuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void checkOrThrow(String bucketKey) {
        int limit = properties.getAdmin().getRateLimitPerWindow();
        Duration window = properties.getAdmin().getRateLimitWindow();
        if (limit <= 0 || window.isZero() || window.isNegative()) {
            return;
        }
        long now = clock.millis();
        long cutoff = now - window.toMillis();
        ArrayDeque<Long> timestamps = windows.computeIfAbsent(bucketKey, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.removeFirst();
            }
            if (timestamps.size() >= limit) {
                throw new BusinessException(ApiErrorCode.ADMIN_LOGIN_RATE_LIMITED);
            }
        }
        maybeCleanup(now, window.toMillis());
    }

    public void recordFailure(String bucketKey) {
        int limit = properties.getAdmin().getRateLimitPerWindow();
        Duration window = properties.getAdmin().getRateLimitWindow();
        if (limit <= 0 || window.isZero() || window.isNegative()) {
            return;
        }
        long now = clock.millis();
        ArrayDeque<Long> timestamps = windows.computeIfAbsent(bucketKey, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            timestamps.addLast(now);
        }
    }

    public void reset() {
        windows.clear();
    }

    private void maybeCleanup(long now, long windowMs) {
        if (windows.size() < 10_000) {
            return;
        }
        long cutoff = now - windowMs;
        Iterator<Map.Entry<String, ArrayDeque<Long>>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ArrayDeque<Long>> entry = it.next();
            ArrayDeque<Long> q = entry.getValue();
            synchronized (q) {
                while (!q.isEmpty() && q.peekFirst() < cutoff) {
                    q.removeFirst();
                }
                if (q.isEmpty()) {
                    it.remove();
                }
            }
        }
    }
}
