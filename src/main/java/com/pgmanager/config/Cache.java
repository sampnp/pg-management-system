package com.pgmanager.config;

import io.vertx.core.Vertx;
import io.vertx.core.net.NetClientOptions;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisOptions;

public final class Cache {

    /** Redis is only a cache: if it is down, a request should fall back to PostgreSQL quickly, not wait for long. */
    private static final int CONNECT_TIMEOUT_MILLIS = 1000;

    private Cache() {
    }

    /**
     * Creates the non-blocking Redis client (vertx-redis-client). It connects lazily on the first command
     * and keeps a small pool of connections, so the application also starts when Redis is not running.
     */
    public static Redis createClient(Vertx vertx, RedisConfig config) {
        return Redis.createClient(vertx, new RedisOptions()
                .setConnectionString(config.connectionString())
                .setNetClientOptions(new NetClientOptions().setConnectTimeout(CONNECT_TIMEOUT_MILLIS)));
    }
}
