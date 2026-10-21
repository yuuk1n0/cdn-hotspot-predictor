package com.yukino.cdn.notification;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

final class FeishuRateLimiter {
    private static final long SECOND_NANOS = 1_000_000_000L;
    private static final long MINUTE_NANOS = 60_000_000_000L;
    private final int qps;
    private final int perMinute;
    private final LongSupplier nanos;
    private final Map<String, Window> windows = new HashMap<>();

    FeishuRateLimiter(int qps, int perMinute, LongSupplier nanos) {
        this.qps = qps;
        this.perMinute = perMinute;
        this.nanos = nanos;
    }

    synchronized boolean tryAcquire(String destinationId) {
        long now = nanos.getAsLong();
        Window window = windows.computeIfAbsent(destinationId, ignored -> new Window());
        evict(window.second, now - SECOND_NANOS);
        evict(window.minute, now - MINUTE_NANOS);
        if (window.second.size() >= qps || window.minute.size() >= perMinute) return false;
        window.second.addLast(now);
        window.minute.addLast(now);
        return true;
    }

    private static void evict(ArrayDeque<Long> attempts, long cutoff) {
        while (!attempts.isEmpty() && attempts.peekFirst() <= cutoff) attempts.removeFirst();
    }

    private static final class Window {
        private final ArrayDeque<Long> second = new ArrayDeque<>();
        private final ArrayDeque<Long> minute = new ArrayDeque<>();
    }
}
