package com.pgmanager.security;

import com.pgmanager.config.RedisBackoff;
import io.vertx.core.Future;
import io.vertx.redis.client.RedisAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalLong;

/**
 * Brute-force protection for POST /api/auth/login, using Redis.
 *
 * Failed logins are counted per (client IP + email): after MAX_FAILURES failures within WINDOW_SECONDS, that
 * combination gets 429 until the window ends. Counting by IP alone would block everyone behind a shared IP
 * (office, proxy); counting by email alone would let anyone lock a user out from anywhere. Successful logins are
 * never counted, and a success clears the counter. Nothing is stored in PostgreSQL and no account is ever locked.
 *
 * Fail-open: Redis is a supporting service, so if it can't be reached, login works without the limit and a
 * warning is logged.
 */
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);
    public static final int MAX_FAILURES = 5;
    public static final int WINDOW_SECONDS = 15 * 60;
    private static final String KEY_PREFIX = "auth:login:fail:";

    private final RedisAPI redis;
    private final RedisBackoff backoff;

    public LoginRateLimiter(RedisAPI redis, RedisBackoff backoff) {
        this.redis = redis;
        this.backoff = backoff;
    }

    /**
     * The key for one IP + email. The pair is hashed, so Redis never holds email addresses or IPs in clear text.
     * The email must already be normalized (trimmed, lower case), so "A@x.com" and "a@x.com" share a counter.
     */
    public static String key(String clientIp, String normalizedEmail) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((clientIp + "|" + normalizedEmail).getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /** Seconds until this IP + email may try again, or empty if a login attempt is allowed now. */
    public Future<OptionalLong> blockedFor(String clientIp, String normalizedEmail) {
        String key = key(clientIp, normalizedEmail);
        return backoff.call(() -> redis.get(key))
                .compose(count -> {
                    if (count == null || count.toLong() < MAX_FAILURES) {
                        return Future.succeededFuture(OptionalLong.empty());
                    }
                    return backoff.call(() -> redis.ttl(key)).map(ttl -> {
                        long seconds = ttl == null ? -1 : ttl.toLong();
                        return OptionalLong.of(seconds > 0 ? seconds : WINDOW_SECONDS);
                    });
                })
                .recover(err -> {
                    log.warn("Login rate limit not checked, Redis unavailable: {}", err.getMessage());
                    return Future.succeededFuture(OptionalLong.empty());
                });
    }

    /**
     * Counts a failed login. INCR creates the counter at 1; EXPIRE ... NX sets the 15-minute window only when the
     * counter has none yet, so the window starts at the first failure and is not extended by later ones.
     */
    public Future<Void> recordFailure(String clientIp, String normalizedEmail) {
        String key = key(clientIp, normalizedEmail);
        return backoff.call(() -> redis.incr(key))
                .compose(count -> backoff.call(() -> redis.expire(List.of(key, String.valueOf(WINDOW_SECONDS), "NX"))))
                .<Void>mapEmpty()
                .recover(err -> {
                    log.warn("Failed login not counted, Redis unavailable: {}", err.getMessage());
                    return Future.succeededFuture();
                });
    }

    /** A successful login clears the failures of that IP + email. */
    public Future<Void> reset(String clientIp, String normalizedEmail) {
        return backoff.call(() -> redis.del(List.of(key(clientIp, normalizedEmail))))
                .<Void>mapEmpty()
                .recover(err -> {
                    log.warn("Login failures not cleared, Redis unavailable: {}", err.getMessage());
                    return Future.succeededFuture();
                });
    }
}
