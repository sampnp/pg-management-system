package com.pgmanager.config;

import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The PostgreSQL pool limits (see the comments in Database for why these values). */
class DatabaseTest {

    @Test
    void poolIsBoundedAndTimesOut() {
        PoolOptions options = Database.poolOptions();

        assertEquals(10, options.getMaxSize());
        assertEquals(100, options.getMaxWaitQueueSize());
        assertEquals(5, options.getConnectionTimeout());
        assertEquals(TimeUnit.SECONDS, options.getConnectionTimeoutUnit());
        assertEquals(5, options.getIdleTimeout());
        assertEquals(TimeUnit.MINUTES, options.getIdleTimeoutUnit());
        assertEquals(30, options.getMaxLifetime());
        assertEquals(TimeUnit.MINUTES, options.getMaxLifetimeUnit());
    }
}
