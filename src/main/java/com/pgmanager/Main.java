package com.pgmanager;

import com.pgmanager.config.AppConfig;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    /** Longer than the HTTP grace period in MainVerticle, shorter than Docker's stop_grace_period. */
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 20;

    public static void main(String[] args) {
        // Read config before starting Vert.x so a missing variable fails immediately with a clear message
        AppConfig config = AppConfig.fromEnv();

        Vertx vertx = Vertx.vertx();
        // "docker compose stop" sends SIGTERM. Closing Vert.x undeploys MainVerticle, so its stop() runs:
        // running requests finish and the connections are closed properly instead of being cut off.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutDown(vertx), "shutdown"));
        vertx.deployVerticle(new MainVerticle(config))
                .onSuccess(id -> log.info("PG Manager started"))
                .onFailure(err -> {
                    log.error("Failed to start PG Manager", err);
                    vertx.close().onComplete(closed -> System.exit(1));
                });
    }

    private static void shutDown(Vertx vertx) {
        log.info("Shutdown requested");
        try {
            vertx.close().await(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("PG Manager stopped");
        } catch (Exception e) {
            log.warn("Shutdown did not finish within {} seconds: {}", SHUTDOWN_TIMEOUT_SECONDS, e.toString());
        }
    }
}
