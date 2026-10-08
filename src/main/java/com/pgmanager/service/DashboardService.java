package com.pgmanager.service;

import com.pgmanager.dto.DashboardSummary;
import com.pgmanager.dto.PropertyDashboard;
import com.pgmanager.exception.NotFoundException;
import com.pgmanager.repository.DashboardCache;
import com.pgmanager.repository.DashboardRepository;
import io.vertx.core.Future;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The PG-wide and the per-property dashboards for ADMIN and MANAGER users (the routes check the role).
 *
 * Cache-aside with Redis: read the cache first; on a miss, run the aggregate query in PostgreSQL and cache
 * the result (see DashboardCache for how writes clear it). Redis is only an optimization - if it is down, or
 * holds something unreadable, the dashboard is simply calculated from PostgreSQL, so a Redis problem never
 * turns into an error for the user.
 */
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private final DashboardRepository dashboardRepository;
    private final DashboardCache dashboardCache;

    public DashboardService(DashboardRepository dashboardRepository, DashboardCache dashboardCache) {
        this.dashboardRepository = dashboardRepository;
        this.dashboardCache = dashboardCache;
    }

    public Future<DashboardSummary> getSummary() {
        return cached(DashboardCache.SUMMARY_KEY, DashboardSummary.class, dashboardRepository::loadSummary);
    }

    /** 404 for an unknown property (nothing is cached for it). */
    public Future<PropertyDashboard> getPropertySummary(UUID propertyId) {
        return cached(DashboardCache.propertyKey(propertyId), PropertyDashboard.class,
                () -> dashboardRepository.loadPropertySummary(propertyId)
                        .map(summary -> summary.orElseThrow(() -> new NotFoundException(PropertyService.PROPERTY_NOT_FOUND))));
    }

    /** What to do with a freshly calculated dashboard, depending on what the cache held. */
    private enum Store { NOTHING, IF_STILL_EMPTY, REPLACE }

    /** A cached dashboard (value), or why there is none and what to store after calculating it. */
    private record Lookup<T>(T value, Store store) {
    }

    private <T> Future<T> cached(String key, Class<T> type, Supplier<Future<T>> load) {
        return lookup(key, type)
                .compose(found -> found.value() != null
                        ? Future.succeededFuture(found.value())
                        : load.get().compose(fresh -> store(key, fresh, found.store()).map(fresh)));
    }

    private <T> Future<Lookup<T>> lookup(String key, Class<T> type) {
        return dashboardCache.get(key)
                .map(cached -> {
                    if (cached.isEmpty()) {
                        // Miss: cache the result, unless a write clears the key while we calculate (SET NX)
                        return new Lookup<T>(null, Store.IF_STILL_EMPTY);
                    }
                    if (DashboardCache.CLEARED.equals(cached.get())) {
                        // The data changed a moment ago: calculate fresh numbers, but don't cache them yet
                        return new Lookup<T>(null, Store.NOTHING);
                    }
                    return parse(cached.get(), type)
                            .map(value -> new Lookup<>(value, Store.NOTHING))
                            .orElseGet(() -> new Lookup<>(null, Store.REPLACE));
                })
                .recover(err -> {
                    // Redis is not usable right now: PostgreSQL only, without trying to write to Redis
                    log.warn("Could not read the dashboard cache, using PostgreSQL: {}", err.getMessage());
                    return Future.succeededFuture(new Lookup<>(null, Store.NOTHING));
                });
    }

    private Future<Void> store(String key, Object dashboard, Store store) {
        Future<Void> stored = switch (store) {
            case NOTHING -> Future.succeededFuture();
            case IF_STILL_EMPTY -> dashboardCache.putIfAbsent(key, Json.encode(dashboard));
            case REPLACE -> dashboardCache.put(key, Json.encode(dashboard));
        };
        // The numbers are already calculated, so a failed cache write only gets logged
        return stored.recover(err -> {
            log.warn("Could not cache the dashboard: {}", err.getMessage());
            return Future.succeededFuture();
        });
    }

    /** Uses the project's Jackson mapper. Anything that is not a complete dashboard is treated as a cache miss. */
    private static <T> Optional<T> parse(String json, Class<T> type) {
        try {
            T value = Json.decodeValue(json, type);
            if (!isComplete(value)) {
                log.warn("Ignoring incomplete cached dashboard");
                return Optional.empty();
            }
            return Optional.of(value);
        } catch (DecodeException e) {
            log.warn("Ignoring invalid cached dashboard JSON: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean isComplete(Object value) {
        return switch (value) {
            case DashboardSummary s -> s.beds() != null && s.tenants() != null && s.payments() != null
                    && s.maintenance() != null && s.generatedAt() != null;
            case PropertyDashboard p -> p.propertyId() != null && p.beds() != null && p.tenants() != null
                    && p.payments() != null && p.maintenance() != null && p.generatedAt() != null;
            default -> false;
        };
    }
}
