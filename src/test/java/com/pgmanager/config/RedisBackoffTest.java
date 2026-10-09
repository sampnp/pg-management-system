package com.pgmanager.config;

import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** After a Redis failure, Redis is skipped for a short pause (with a fake clock, so no waiting). */
class RedisBackoffTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final RedisBackoff backoff = new RedisBackoff(Duration.ofSeconds(5), now::get);
    private final AtomicInteger calls = new AtomicInteger();

    @Test
    void successfulCommandsAlwaysRun() {
        assertTrue(backoff.call(this::succeeds).succeeded());
        assertTrue(backoff.call(this::succeeds).succeeded());
        assertEquals(2, calls.get());
    }

    @Test
    void afterAFailureRedisIsSkippedForThePauseThenTriedAgain() {
        assertTrue(backoff.call(this::fails).failed());
        assertEquals(1, calls.get());

        now.addAndGet(4_999);
        assertTrue(backoff.call(this::succeeds).failed(), "still paused: fails at once without calling Redis");
        assertEquals(1, calls.get());

        now.addAndGet(1);
        assertTrue(backoff.call(this::succeeds).succeeded(), "pause over: Redis is tried again");
        assertEquals(2, calls.get());
    }

    @Test
    void noPauseMeansEveryCommandRuns() {
        RedisBackoff none = RedisBackoff.none();
        none.call(this::fails);
        none.call(this::succeeds);
        assertEquals(2, calls.get());
    }

    private Future<String> succeeds() {
        calls.incrementAndGet();
        return Future.succeededFuture("OK");
    }

    private Future<String> fails() {
        calls.incrementAndGet();
        return Future.failedFuture(new ConnectException("Connection refused"));
    }
}
