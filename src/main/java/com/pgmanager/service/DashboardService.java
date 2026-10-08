package com.pgmanager.service;

import com.pgmanager.dto.DashboardSummary;
import com.pgmanager.repository.DashboardCache;
import com.pgmanager.repository.DashboardRepository;
import io.vertx.core.Future;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * PG-wide statistics for ADMIN and MANAGER users (the route checks the role).
 *
 * Cache-aside with Redis: read the cache first; on a miss, run the aggregate query in PostgreSQL and cache
 * the result. Redis is only an optimization - if it is down, or holds something unreadable, the dashboard
 * is simply calculated from PostgreSQL, so a Redis problem never turns into an error for the user.
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
        return readCache()
                .compose(cached -> cached.isPresent()
                        ? Future.succeededFuture(cached.get())
                        : loadAndCache());
    }

    /** The cached dashboard, or empty on a miss, a Redis failure or unreadable cached JSON. */
    private Future<Optional<DashboardSummary>> readCache() {
        return dashboardCache.get()
                .map(json -> json.flatMap(DashboardService::parse))
                .recover(err -> {
                    log.warn("Could not read the dashboard cache, using PostgreSQL: {}", err.getMessage());
                    return Future.succeededFuture(Optional.empty());
                });
    }

    private Future<DashboardSummary> loadAndCache() {
        return dashboardRepository.loadSummary()
                .compose(summary -> dashboardCache.put(Json.encode(summary))
                        .recover(err -> {
                            // The numbers are already calculated, so still return them
                            log.warn("Could not cache the dashboard: {}", err.getMessage());
                            return Future.succeededFuture();
                        })
                        .map(summary));
    }

    /** Uses the project's Jackson mapper. Anything that is not a complete dashboard is treated as a cache miss. */
    private static Optional<DashboardSummary> parse(String json) {
        try {
            DashboardSummary summary = Json.decodeValue(json, DashboardSummary.class);
            if (summary.beds() == null || summary.tenants() == null || summary.payments() == null
                    || summary.maintenance() == null || summary.generatedAt() == null) {
                log.warn("Ignoring incomplete cached dashboard");
                return Optional.empty();
            }
            return Optional.of(summary);
        } catch (DecodeException e) {
            log.warn("Ignoring invalid cached dashboard JSON: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
