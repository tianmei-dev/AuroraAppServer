package com.aurora.appserver.trial.service;

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
 * Lightweight in-memory sliding-window rate limiter (no Redis).
 * Mitigates anonymous abuse only — cannot stop forged deviceKey values.
 */
@Component
public class InMemoryRateLimiter {

    private final Map<String, ArrayDeque<Long>> windows = new ConcurrentHashMap<>();
    private final AuroraAuthProperties properties;
    private final Clock clock;

    public InMemoryRateLimiter(AuroraAuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void checkOrThrow(String bucketKey) {
        int limit = properties.getTrial().getRateLimitPerWindow();
        Duration window = properties.getTrial().getRateLimitWindow();
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
                throw new BusinessException(ApiErrorCode.TRIAL_RATE_LIMITED);
            }
            timestamps.addLast(now);
        }
        maybeCleanup(now, window.toMillis());
    }

    /** Test helper: clear all buckets. */
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
