package com.yukino.cdn.files;

import java.util.ArrayDeque;
import java.util.function.LongSupplier;
import static com.yukino.cdn.files.CStoreSettings.problem;

/** One shared instance for both upstream endpoints. Counts HTTP requests, never files. */
final class CStoreLimiter {
    private final int qps, concurrency;
    private final LongSupplier nanos;
    private final ArrayDeque<Long> started = new ArrayDeque<>();
    private int inFlight;
    CStoreLimiter(int qps, int concurrency, LongSupplier nanos) { this.qps = qps; this.concurrency = concurrency; this.nanos = nanos; }
    synchronized Lease acquire() {
        long now = nanos.getAsLong();
        while (!started.isEmpty() && now - started.peekFirst() >= 1_000_000_000L) started.removeFirst();
        if (inFlight >= concurrency || started.size() >= qps) throw problem(429, "CSTORE_RATE_LIMITED", "文件服务请求容量已满，请稍后重试。");
        started.addLast(now); inFlight++;
        return new Lease();
    }
    final class Lease implements AutoCloseable {
        private boolean closed;
        public void close() { synchronized (CStoreLimiter.this) { if (!closed) { inFlight--; closed = true; } } }
    }
}
