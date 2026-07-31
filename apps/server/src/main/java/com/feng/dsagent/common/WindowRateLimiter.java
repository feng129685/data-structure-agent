package com.feng.dsagent.common;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public final class WindowRateLimiter {

    private static final int DEFAULT_MAXIMUM_TRACKED_KEYS = 10_000;

    private final int maximumRequests;
    private final Duration window;
    private final int maximumTrackedKeys;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    public WindowRateLimiter(int maximumRequests, Duration window) {
        this(maximumRequests, window, DEFAULT_MAXIMUM_TRACKED_KEYS);
    }

    WindowRateLimiter(int maximumRequests, Duration window, int maximumTrackedKeys) {
        if (maximumRequests < 1) {
            throw new IllegalArgumentException("maximumRequests must be positive");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
        if (maximumTrackedKeys < 1) {
            throw new IllegalArgumentException("maximumTrackedKeys must be positive");
        }
        this.maximumRequests = maximumRequests;
        this.window = window;
        this.maximumTrackedKeys = maximumTrackedKeys;
    }

    public boolean allow(String key, Instant now) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        if (now == null) {
            throw new IllegalArgumentException("now must not be null");
        }
        if (!ensureCapacity(key, now)) {
            return false;
        }

        Counter next = counters.compute(key, (ignored, current) -> {
            if (current == null || !now.isBefore(current.startedAt().plus(window))) {
                return new Counter(now, 1, true);
            }
            if (current.count() >= maximumRequests) {
                return new Counter(current.startedAt(), current.count(), false);
            }
            return new Counter(current.startedAt(), current.count() + 1, true);
        });
        return next.allowed();
    }

    int trackedKeyCount() {
        return counters.size();
    }

    private boolean ensureCapacity(String key, Instant now) {
        if (counters.containsKey(key)) {
            return true;
        }
        synchronized (counters) {
            if (counters.containsKey(key)) {
                return true;
            }
            if (counters.size() >= maximumTrackedKeys) {
                counters.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().startedAt().plus(window)));
            }
            if (counters.size() >= maximumTrackedKeys) {
                return false;
            }
            counters.put(key, new Counter(now, 0, true));
            return true;
        }
    }

    private record Counter(Instant startedAt, int count, boolean allowed) {
    }
}
