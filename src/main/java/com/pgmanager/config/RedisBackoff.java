package com.pgmanager.config;

import io.vertx.core.Future;

import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * After a Redis command fails, skip Redis for a short while instead of letting every request wait for the
 * connect timeout again. Redis is optional here (dashboard cache, login rate limit), so callers already treat
 * a failed command as "Redis unavailable"; this just makes that answer immediate during an outage.
 * After the pause, the next command tries Redis again.
 */
public final class RedisBackoff {

    public static final Duration DEFAULT_PAUSE = Duration.ofSeconds(5);

    private final long pauseMillis;
    private final LongSupplier clockMillis;
    private volatile long skipUntil;

    public RedisBackoff(Duration pause, LongSupplier clockMillis) {
        this.pauseMillis = pause.toMillis();
        this.clockMillis = clockMillis;
    }

    public RedisBackoff() {
        this(DEFAULT_PAUSE, System::currentTimeMillis);
    }

    /** No pause: every command goes to Redis (unit tests with a mocked Redis). */
    public static RedisBackoff none() {
        return new RedisBackoff(Duration.ZERO, System::currentTimeMillis);
    }

    /** Runs the Redis command, or fails at once while Redis is paused after a recent failure. */
    public <T> Future<T> call(Supplier<Future<T>> command) {
        if (clockMillis.getAsLong() < skipUntil) {
            return Future.failedFuture(new IllegalStateException("Redis skipped after a recent failure"));
        }
        return command.get().onFailure(err -> skipUntil = clockMillis.getAsLong() + pauseMillis);
    }
}
