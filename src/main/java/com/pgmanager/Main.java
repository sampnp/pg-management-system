package com.pgmanager;

import com.pgmanager.config.AppConfig;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        // Read config before starting Vert.x so a missing variable fails immediately with a clear message
        AppConfig config = AppConfig.fromEnv();

        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new MainVerticle(config))
                .onSuccess(id -> log.info("PG Manager started"))
                .onFailure(err -> {
                    log.error("Failed to start PG Manager", err);
                    vertx.close().onComplete(closed -> System.exit(1));
                });
    }
}
