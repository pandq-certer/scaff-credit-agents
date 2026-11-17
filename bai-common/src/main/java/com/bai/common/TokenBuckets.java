package com.bai.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 令牌桶限流（按调用方）：QPS + 突发容量，超限由调用方抛限流错误码（如 OCR_1008）
 */
public class TokenBuckets {

    private static class Bucket {
        double tokens;
        long lastRefill;
        final double capacity;
        final double ratePerMillis;

        Bucket(int qps, int burst) {
            this.capacity = Math.max(burst, qps);
            this.ratePerMillis = qps / 1000.0;
            this.tokens = capacity;
            this.lastRefill = System.nanoTime();
        }

        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - lastRefill) / 1_000_000_000.0 * ratePerMillis);
            lastRefill = now;
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int qps;
    private final int burst;

    public TokenBuckets(int qps, int burst) {
        this.qps = qps;
        this.burst = burst;
    }

    /**
     * @param callerKey 调用方标识（clientId / IP）
     */
    public boolean allow(String callerKey) {
        return buckets.computeIfAbsent(callerKey, k -> new Bucket(qps, burst)).tryAcquire();
    }
}
