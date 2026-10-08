package com.pgmanager.repository;

import io.vertx.core.Future;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The cached dashboards in Redis, as JSON: "dashboard:summary" for the whole PG and
 * "dashboard:property:<id>" for each property. Redis is only a cache; PostgreSQL stays the source of truth,
 * so losing these keys (or Redis itself) never loses data.
 *
 * Two things keep the cache from showing old numbers after a write:
 * - A cleared key is not deleted but set to CLEARED for a few seconds, and a new dashboard is only cached
 *   if the key is empty (SET ... NX). So a request that started calculating before the write can't put its
 *   older numbers back into the cache after the write cleared it.
 * - If a clear fails because Redis can't be reached, the key is remembered and cleared again before the
 *   cache is read next time. Until that works, the cache is not used at all.
 */
public class DashboardCache {

    private static final Logger log = LoggerFactory.getLogger(DashboardCache.class);
    public static final String SUMMARY_KEY = "dashboard:summary";
    private static final String PROPERTY_KEY_PREFIX = "dashboard:property:";
    /** Value of a key that was cleared within the last CLEARED_SECONDS: "recalculate, but don't cache yet". */
    public static final String CLEARED = "cleared";
    /** Much longer than a dashboard query takes, much shorter than the cache TTL. */
    private static final int CLEARED_SECONDS = 5;

    private final RedisAPI redis;
    private final int ttlSeconds;
    /** Keys whose clear failed; this application instance clears them again before trusting the cache. */
    private final Set<String> pendingClears = ConcurrentHashMap.newKeySet();

    public DashboardCache(RedisAPI redis, int ttlSeconds) {
        this.redis = redis;
        this.ttlSeconds = ttlSeconds;
    }

    public static String propertyKey(UUID propertyId) {
        return PROPERTY_KEY_PREFIX + propertyId;
    }

    /**
     * The cached value (dashboard JSON or CLEARED), or empty if nothing is cached. Fails if Redis can't be
     * reached, or if an earlier clear still could not be repeated.
     */
    public Future<Optional<String>> get(String key) {
        return clearPending()
                .compose(v -> redis.get(key))
                .map(response -> Optional.ofNullable(response).map(Response::toString));
    }

    /**
     * Caches a freshly calculated dashboard, but only if the key is empty: SET key value EX ttl NX.
     * If a write cleared the key in the meantime it holds CLEARED, and the possibly older value is not stored.
     */
    public Future<Void> putIfAbsent(String key, String json) {
        return redis.set(List.of(key, json, "EX", String.valueOf(ttlSeconds), "NX")).mapEmpty();
    }

    /** Replaces whatever is cached (used for a value that could not be read). SET key value EX ttl. */
    public Future<Void> put(String key, String json) {
        return redis.set(List.of(key, json, "EX", String.valueOf(ttlSeconds))).mapEmpty();
    }

    /**
     * Clears the PG-wide dashboard and the dashboards of the properties whose numbers changed. Called after a
     * successful write (after its transaction has committed), so the next request recalculates them.
     * Other properties keep their cached dashboard. Never fails: the write itself already succeeded,
     * so a Redis problem is logged and the clear is repeated later (see get).
     */
    public Future<Void> invalidate(Set<UUID> changedProperties) {
        List<String> keys = new ArrayList<>();
        keys.add(SUMMARY_KEY);
        changedProperties.forEach(propertyId -> keys.add(propertyKey(propertyId)));
        return markCleared(keys)
                .recover(err -> {
                    pendingClears.addAll(keys);
                    log.warn("Could not clear the dashboard cache, will retry before it is used again: {}", err.getMessage());
                    return Future.succeededFuture();
                });
    }

    private Future<Void> clearPending() {
        if (pendingClears.isEmpty()) {
            return Future.succeededFuture();
        }
        List<String> keys = List.copyOf(pendingClears);
        return markCleared(keys)
                .onSuccess(v -> {
                    keys.forEach(pendingClears::remove);
                    log.info("Cleared {} dashboard cache key(s) that could not be cleared earlier", keys.size());
                });
    }

    /** SET key CLEARED EX CLEARED_SECONDS for every key. */
    private Future<Void> markCleared(Collection<String> keys) {
        List<Future<Response>> sets = keys.stream()
                .map(key -> redis.set(List.of(key, CLEARED, "EX", String.valueOf(CLEARED_SECONDS))))
                .toList();
        return Future.all(sets).mapEmpty();
    }
}
