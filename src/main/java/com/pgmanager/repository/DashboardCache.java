package com.pgmanager.repository;

import io.vertx.core.Future;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The cached dashboards in Redis, as JSON: "dashboard:summary" for the whole PG and
 * "dashboard:property:<id>" for each property. Redis is only a cache; PostgreSQL stays the source of truth,
 * so losing these keys (or Redis itself) never loses data.
 */
public class DashboardCache {

    private static final Logger log = LoggerFactory.getLogger(DashboardCache.class);
    public static final String SUMMARY_KEY = "dashboard:summary";
    private static final String PROPERTY_KEY_PREFIX = "dashboard:property:";

    private final RedisAPI redis;
    private final int ttlSeconds;

    public DashboardCache(RedisAPI redis, int ttlSeconds) {
        this.redis = redis;
        this.ttlSeconds = ttlSeconds;
    }

    public static String propertyKey(UUID propertyId) {
        return PROPERTY_KEY_PREFIX + propertyId;
    }

    /** The cached JSON, or empty if nothing is cached. Fails if Redis can't be reached. */
    public Future<Optional<String>> get(String key) {
        return redis.get(key)
                .map(response -> Optional.ofNullable(response).map(Response::toString));
    }

    /**
     * Caches the JSON with an expiry: SET key value EX seconds (the modern form of SETEX, which Redis deprecated).
     * The TTL is the safety net: even if an invalidation is missed, the dashboard is never more than
     * ttlSeconds out of date. Fails if Redis can't be reached.
     */
    public Future<Void> put(String key, String json) {
        return redis.set(List.of(key, json, "EX", String.valueOf(ttlSeconds))).mapEmpty();
    }

    /**
     * Deletes the PG-wide dashboard and the dashboards of the properties whose numbers changed. Called after a
     * successful write (after its transaction has committed), so the next request recalculates them.
     * Other properties keep their cached dashboard. Never fails: the write itself already succeeded,
     * so a Redis problem is only logged.
     */
    public Future<Void> invalidate(Set<UUID> changedProperties) {
        List<String> keys = new ArrayList<>();
        keys.add(SUMMARY_KEY);
        changedProperties.forEach(propertyId -> keys.add(propertyKey(propertyId)));
        return redis.del(keys)
                .<Void>mapEmpty()
                .recover(err -> {
                    log.warn("Could not clear the dashboard cache, it may be out of date for up to {} seconds: {}",
                            ttlSeconds, err.getMessage());
                    return Future.succeededFuture();
                });
    }
}
