package com.yukino.cdn.notification;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class FeishuRateLimiterTest {
    @Test void secondWindowIsNonBlockingAndIsolatedByDestination() {
        var now = new AtomicLong();
        var limiter = new FeishuRateLimiter(2, 100, now::get);
        assertTrue(limiter.tryAcquire("business"));
        assertTrue(limiter.tryAcquire("business"));
        assertFalse(limiter.tryAcquire("business"));
        assertTrue(limiter.tryAcquire("demo"));
        now.set(1_000_000_000L);
        assertTrue(limiter.tryAcquire("business"));
    }

    @Test void minuteWindowRejectsUntilOldestAttemptExpires() {
        var now = new AtomicLong();
        var limiter = new FeishuRateLimiter(5, 3, now::get);
        assertTrue(limiter.tryAcquire("business"));
        now.set(1_000_000_000L);
        assertTrue(limiter.tryAcquire("business"));
        now.set(2_000_000_000L);
        assertTrue(limiter.tryAcquire("business"));
        now.set(59_999_999_999L);
        assertFalse(limiter.tryAcquire("business"));
        now.set(60_000_000_000L);
        assertTrue(limiter.tryAcquire("business"));
    }
}
