package com.pgmanager;

import com.pgmanager.config.AppConfig;
import com.pgmanager.config.Database;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.ext.web.Router;
import io.vertx.sqlclient.Pool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wires the application together (manual constructor injection) and starts the HTTP server.
 * Every step returns a Future, and compose() chains them so each step starts only after the previous one succeeds.
 */
public class MainVerticle extends VerticleBase {

    private static final Logger log = LoggerFactory.getLogger(MainVerticle.class);

    private final AppConfig config;
    private Pool pool;

    public MainVerticle(AppConfig config) {
        this.config = config;
    }

    @Override
    public Future<?> start() {
        return Database.migrate(vertx, config.database())
                .compose(migrated -> {
                    pool = Database.createPool(vertx, config.database());
                    return vertx.createHttpServer()
                            .requestHandler(createRouter())
                            .listen(config.httpPort());
                })
                .onSuccess(server -> log.info("HTTP server listening on port {}", server.actualPort()));
    }

    @Override
    public Future<?> stop() {
        return pool != null ? pool.close() : Future.succeededFuture();
    }

    private Router createRouter() {
        return Router.router(vertx);
    }
}
