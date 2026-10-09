package com.pgmanager.security;

import com.pgmanager.config.RedisBackoff;
import io.vertx.core.Future;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.List;
import java.util.OptionalLong;

import static com.pgmanager.TestFutures.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Redis commands of the login rate limiter, with a mocked Redis client. */
class LoginRateLimiterTest {

    private static final String IP = "203.0.113.7";
    private static final String EMAIL = "sambit@example.com";
    private static final String KEY = LoginRateLimiter.key(IP, EMAIL);

    private RedisAPI redis;
    private LoginRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redis = mock(RedisAPI.class);
        limiter = new LoginRateLimiter(redis, RedisBackoff.none());
    }

    @Test
    void keyIsAHashOfIpAndEmailWithNoPersonalDataInIt() {
        assertTrue(KEY.startsWith("auth:login:fail:"));
        assertEquals("auth:login:fail:".length() + 64, KEY.length());
        assertFalse(KEY.contains(EMAIL));
        assertFalse(KEY.contains(IP));
        assertNotEquals(KEY, LoginRateLimiter.key("198.51.100.1", EMAIL), "another IP has its own counter");
        assertNotEquals(KEY, LoginRateLimiter.key(IP, "other@example.com"), "another email has its own counter");
    }

    @Test
    void fewerThanFiveFailuresIsNotBlocked() throws Exception {
        Response reply4 = number(4);
        when(redis.get(KEY)).thenReturn(Future.succeededFuture(reply4));

        assertEquals(OptionalLong.empty(), await(limiter.blockedFor(IP, EMAIL)));
        verify(redis, never()).ttl(any());
    }

    @Test
    void noCounterIsNotBlocked() throws Exception {
        when(redis.get(KEY)).thenReturn(Future.succeededFuture(null));

        assertEquals(OptionalLong.empty(), await(limiter.blockedFor(IP, EMAIL)));
    }

    @Test
    void fiveFailuresBlockForTheRestOfTheWindow() throws Exception {
        Response reply5 = number(5);
        when(redis.get(KEY)).thenReturn(Future.succeededFuture(reply5));
        Response reply420 = number(420);
        when(redis.ttl(KEY)).thenReturn(Future.succeededFuture(reply420));

        assertEquals(OptionalLong.of(420), await(limiter.blockedFor(IP, EMAIL)));
    }

    @Test
    void counterWithoutTtlStillBlocksForTheFullWindow() throws Exception {
        Response reply7 = number(7);
        when(redis.get(KEY)).thenReturn(Future.succeededFuture(reply7));
        Response replyMinus1 = number(-1);
        when(redis.ttl(KEY)).thenReturn(Future.succeededFuture(replyMinus1));

        assertEquals(OptionalLong.of(900), await(limiter.blockedFor(IP, EMAIL)));
    }

    @Test
    void failureIncrementsAndStartsTheWindowOnlyOnce() throws Exception {
        Response one = number(1);
        when(redis.incr(KEY)).thenReturn(Future.succeededFuture(one));
        when(redis.expire(anyList())).thenReturn(Future.succeededFuture(one));

        await(limiter.recordFailure(IP, EMAIL));

        verify(redis).incr(KEY);
        // NX: only sets the expiry if the key has none, so later failures don't extend the window
        verify(redis).expire(List.of(KEY, "900", "NX"));
    }

    @Test
    void successDeletesTheCounter() throws Exception {
        Response reply1 = number(1);
        when(redis.del(anyList())).thenReturn(Future.succeededFuture(reply1));

        await(limiter.reset(IP, EMAIL));

        verify(redis).del(List.of(KEY));
    }

    @Test
    void redisDownMeansNoLimitButNeverAnError() throws Exception {
        ConnectException down = new ConnectException("Connection refused");
        when(redis.get(any())).thenReturn(Future.failedFuture(down));
        when(redis.incr(any())).thenReturn(Future.failedFuture(down));
        when(redis.del(anyList())).thenReturn(Future.failedFuture(down));

        // Fail-open: login keeps working when Redis is unavailable
        assertEquals(OptionalLong.empty(), await(limiter.blockedFor(IP, EMAIL)));
        await(limiter.recordFailure(IP, EMAIL));
        await(limiter.reset(IP, EMAIL));
    }

    /** Created before when(...): Mockito can't create a mock while another stubbing is in progress. */
    private static Response number(long value) {
        Response response = mock(Response.class);
        when(response.toLong()).thenReturn(value);
        return response;
    }
}
