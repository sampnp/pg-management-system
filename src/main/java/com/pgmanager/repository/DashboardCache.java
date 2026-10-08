package com.pgmanager.repository;

import io.vertx.core.Future;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * The cached dashboard in Redis: one key holding the dashboard JSON. Redis is only a cache;
 * PostgreSQL stays the source of truth, so losing this key (or Redis itself) never loses data.
 */
public class DashboardCache {

    private static final Logger log = LoggerFactory.getLogger(DashboardCache.class);
    static final String KEY = "dashboard:summary";

    private final RedisAPI redis;
    private final int ttlSeconds;

    public DashboardCache(RedisAPI redis, int ttlSeconds) {
        this.redis = redis;
        this.ttlSeconds = ttlSeconds;
    }

    /** The cached JSON, or empty if nothing is cached. Fails if Redis can't be reached. */
    public Future<Optional<String>> get() {
        return redis.get(KEY)
                .map(response -> Optional.ofNullable(response).map(Response::toString));
    }

    /**
     * Caches the JSON with an expiry: SET key value EX seconds (the modern form of SETEX, which Redis deprecated).
     * The TTL is the safety net: even if an invalidation is missed, the dashboard is never more than
     * ttlSeconds out of date. Fails if Redis can't be reached.
     */
    public Future<Void> put(String json) {
        return redis.set(List.of(KEY, json, "EX", String.valueOf(ttlSeconds))).mapEmpty();
    }

    /**
     * Deletes the cached dashboard. Called after a write that changes the numbers on it, so the next request
     * recalculates them. Never fails: the write itself already succeeded, so a Redis problem is only logged.
     */
    public Future<Void> invalidate() {
        return redis.del(List.of(KEY))
                .<Void>mapEmpty()
                .recover(err -> {
                    log.warn("Could not clear the dashboard cache, it may be out of date for up to {} seconds: {}",
                            ttlSeconds, err.getMessage());
                    return Future.succeededFuture();
                });
    }
}
