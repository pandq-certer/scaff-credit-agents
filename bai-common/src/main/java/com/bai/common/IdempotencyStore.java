package com.bai.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等缓存（MVP 内存实现）：requestId 相同重复提交返回首次结果。
 * <p>生产环境替换为 Redis 实现（结果缓存 24h，TTL 按各原子约定）。
 */
public class IdempotencyStore {

    private record Entry(Object value, long expireAt) {}

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public IdempotencyStore(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    /** 默认 24h */
    public IdempotencyStore() {
        this(24 * 3600 * 1000L);
    }

    /**
     * 命中且未过期返回首次结果；否则返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String atom, String requestId) {
        Entry e = cache.get(atom + ":" + requestId);
        if (e == null) {
            return null;
        }
        if (System.currentTimeMillis() > e.expireAt()) {
            cache.remove(atom + ":" + requestId);
            return null;
        }
        return (T) e.value();
    }

    public void put(String atom, String requestId, Object value) {
        cache.put(atom + ":" + requestId, new Entry(value, System.currentTimeMillis() + ttlMillis));
    }
}
