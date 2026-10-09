package com.pgmanager.config;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClientOptions;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public final class Database {

    private static final Logger log = LoggerFactory.getLogger(Database.class);
    /**
     * 10 connections: queries here are short and non-blocking, so one connection serves many requests per second.
     * PostgreSQL allows 100 connections by default, so several app copies plus admin tools still fit.
     */
    private static final int MAX_POOL_SIZE = 10;
    /** Requests waiting for a free connection. Beyond that, fail at once instead of piling up in memory. */
    private static final int MAX_WAIT_QUEUE_SIZE = 100;
    /** How long a request may wait for a free connection, and how long opening a new one may take. */
    private static final int CONNECTION_TIMEOUT_SECONDS = 5;

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

        return PgBuilder.pool()
                .with(poolOptions())
                .with(new NetClientOptions().setConnectTimeout(CONNECTION_TIMEOUT_SECONDS * 1000))
                .connectingTo(connectOptions)
                .using(vertx)
                .build();
    }

    static PoolOptions poolOptions() {
        return new PoolOptions()
                .setMaxSize(MAX_POOL_SIZE)
                .setMaxWaitQueueSize(MAX_WAIT_QUEUE_SIZE)
                .setConnectionTimeout(CONNECTION_TIMEOUT_SECONDS).setConnectionTimeoutUnit(TimeUnit.SECONDS)
                // Close connections nobody used for a while, and replace old ones now and then
                // (e.g. after a database failover, no connection stays pointed at the old server forever)
                .setIdleTimeout(5).setIdleTimeoutUnit(TimeUnit.MINUTES)
                .setMaxLifetime(30).setMaxLifetimeUnit(TimeUnit.MINUTES);
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
