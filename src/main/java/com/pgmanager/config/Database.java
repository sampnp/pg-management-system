package com.pgmanager.config;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Database {

    private static final Logger log = LoggerFactory.getLogger(Database.class);
    private static final int MAX_POOL_SIZE = 10;

    private Database() {
    }

    /**
     * Creates the reactive PostgreSQL connection pool.
     * Queries on this pool never block a thread: they return a Future that completes
     * on the event loop when PostgreSQL answers.
     */
    public static Pool createPool(Vertx vertx, DatabaseConfig config) {
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(config.host())
                .setPort(config.port())
                .setDatabase(config.name())
                .setUser(config.user())
                .setPassword(config.password());

        PoolOptions poolOptions = new PoolOptions().setMaxSize(MAX_POOL_SIZE);

        return PgBuilder.pool()
                .with(poolOptions)
                .connectingTo(connectOptions)
                .using(vertx)
                .build();
    }

    /**
     * Runs Flyway migrations from src/main/resources/db/migration.
     * Flyway uses JDBC, which BLOCKS the calling thread. executeBlocking() runs it on a
     * worker thread, so the event loop stays free. This is the only place JDBC is used.
     */
    public static Future<Void> migrate(Vertx vertx, DatabaseConfig config) {
        return vertx.executeBlocking(() -> {
            MigrateResult result = Flyway.configure()
                    .dataSource(config.jdbcUrl(), config.user(), config.password())
                    .load()
                    .migrate();
            log.info("Database migrations applied: {} (schema version {})",
                    result.migrationsExecuted, result.targetSchemaVersion);
            return null;
        });
    }
}
