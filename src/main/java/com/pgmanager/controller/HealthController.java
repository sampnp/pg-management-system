package com.pgmanager.controller;

import com.pgmanager.dto.HealthResponse;
import io.vertx.ext.web.RoutingContext;
import io.vertx.sqlclient.Pool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final Pool pool;

    public HealthController(Pool pool) {
        this.pool = pool;
    }

    /** GET /api/health - reports whether the app is up and PostgreSQL is reachable. */
    public void check(RoutingContext ctx) {
        // The handler returns immediately; the response is sent later, when the query's Future completes.
        pool.query("SELECT 1").execute()
                .onSuccess(rows -> ctx.json(new HealthResponse("UP", "UP")))
                .onFailure(err -> {
                    log.warn("Health check: database unreachable", err);
                    ctx.response().setStatusCode(503);
                    ctx.json(new HealthResponse("DOWN", "DOWN"));
                });
    }
}
