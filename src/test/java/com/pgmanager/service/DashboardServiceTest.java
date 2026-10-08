package com.pgmanager.service;

import com.pgmanager.config.JsonConfig;
import com.pgmanager.dto.DashboardSummary;
import com.pgmanager.repository.DashboardCache;
import com.pgmanager.repository.DashboardRepository;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.time.Instant;
import java.util.List;

import static com.pgmanager.TestFutures.await;
import static com.pgmanager.TestFutures.awaitFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DashboardService with the real DashboardCache on top of a mocked Redis client, so the tests also check
 * the exact Redis commands: key, TTL and what happens when Redis fails.
 */
class DashboardServiceTest {

    private static final String KEY = "dashboard:summary";
    private static final int TTL_SECONDS = 60;

    private final DashboardSummary summary = new DashboardSummary(3, 24,
            new DashboardSummary.Beds(72, 18, 54),
            new DashboardSummary.Tenants(4, 54, 21),
            new DashboardSummary.Payments(140, 18, new BigDecimal("532000.00"), new BigDecimal("72000.50")),
            new DashboardSummary.Maintenance(7, 3, 22, 9, 2),
            Instant.parse("2026-10-09T10:15:30.123456Z"));

    private RedisAPI redis;
    private DashboardRepository dashboardRepository;
    private DashboardCache dashboardCache;
    private DashboardService dashboardService;

    @BeforeAll
    static void configureJson() {
        // The same Jackson setup the application uses (java.time support for generatedAt)
        JsonConfig.configure();
    }

    @BeforeEach
    void setUp() {
        redis = mock(RedisAPI.class);
        dashboardRepository = mock(DashboardRepository.class);
        dashboardCache = new DashboardCache(redis, TTL_SECONDS);
        dashboardService = new DashboardService(dashboardRepository, dashboardCache);
        when(dashboardRepository.loadSummary()).thenReturn(Future.succeededFuture(summary));
    }

    @Test
    void cacheHitIsReturnedWithoutQueryingPostgres() throws Exception {
        cached(Json.encode(summary));

        DashboardSummary result = await(dashboardService.getSummary());

        assertEquals(summary, result);
        verify(redis).get(KEY);
        verify(dashboardRepository, never()).loadSummary();
        verify(redis, never()).set(anyList());
    }

    @Test
    void cacheMissQueriesPostgresAndCachesTheResultWithTheTtl() throws Exception {
        cacheIsEmpty();
        when(redis.set(anyList())).thenReturn(Future.succeededFuture());

        DashboardSummary result = await(dashboardService.getSummary());

        assertSame(summary, result);
        verify(dashboardRepository).loadSummary();
        // SET dashboard:summary <json> EX 60
        verify(redis).set(List.of(KEY, Json.encode(summary), "EX", "60"));
    }

    @Test
    void cachedJsonReadsBackAsTheSameSummary() throws Exception {
        cacheIsEmpty();
        when(redis.set(anyList())).thenReturn(Future.succeededFuture());
        await(dashboardService.getSummary());

        // What was written to Redis must decode back to exactly the same numbers (BigDecimal scale, timestamp)
        String written = Json.encode(summary);
        cached(written);
        assertEquals(summary, await(dashboardService.getSummary()));
    }

    @Test
    void ttlComesFromConfiguration() throws Exception {
        dashboardService = new DashboardService(dashboardRepository, new DashboardCache(redis, 15));
        cacheIsEmpty();
        when(redis.set(anyList())).thenReturn(Future.succeededFuture());

        await(dashboardService.getSummary());

        verify(redis).set(List.of(KEY, Json.encode(summary), "EX", "15"));
    }

    @Test
    void redisGetFailureFallsBackToPostgres() throws Exception {
        when(redis.get(KEY)).thenReturn(Future.failedFuture(new ConnectException("Connection refused")));
        when(redis.set(anyList())).thenReturn(Future.succeededFuture());

        assertSame(summary, await(dashboardService.getSummary()));
        verify(dashboardRepository).loadSummary();
    }

    @Test
    void redisSetFailureStillReturnsTheDatabaseResult() throws Exception {
        cacheIsEmpty();
        when(redis.set(anyList())).thenReturn(Future.failedFuture(new ConnectException("Connection refused")));

        assertSame(summary, await(dashboardService.getSummary()));
    }

    @Test
    void redisCompletelyDownStillServesTheDashboard() throws Exception {
        when(redis.get(KEY)).thenReturn(Future.failedFuture(new ConnectException("Connection refused")));
        when(redis.set(anyList())).thenReturn(Future.failedFuture(new ConnectException("Connection refused")));

        assertSame(summary, await(dashboardService.getSummary()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json at all",
            "{\"properties\": 3",
            "[1, 2, 3]",
            "{}",
            "{\"properties\": \"many\"}",
            "{\"properties\": 3, \"rooms\": 24, \"unexpected\": true}"})
    void invalidCachedJsonIsIgnoredAndReplaced(String badJson) throws Exception {
        cached(badJson);
        when(redis.set(anyList())).thenReturn(Future.succeededFuture());

        assertSame(summary, await(dashboardService.getSummary()));
        verify(dashboardRepository).loadSummary();
        verify(redis).set(List.of(KEY, Json.encode(summary), "EX", "60"));
    }

    @Test
    void postgresFailureIsReportedBecausePostgresIsTheSourceOfTruth() throws Exception {
        cacheIsEmpty();
        RuntimeException dbDown = new RuntimeException("database unavailable");
        when(dashboardRepository.loadSummary()).thenReturn(Future.failedFuture(dbDown));

        assertSame(dbDown, awaitFailure(dashboardService.getSummary()));
        verify(redis, never()).set(anyList());
    }

    @Test
    void invalidateDeletesTheDashboardKey() throws Exception {
        when(redis.del(anyList())).thenReturn(Future.succeededFuture());

        await(dashboardCache.invalidate());

        verify(redis).del(List.of(KEY));
    }

    @Test
    void invalidateNeverFailsEvenWhenRedisIsDown() throws Exception {
        when(redis.del(anyList())).thenReturn(Future.failedFuture(new ConnectException("Connection refused")));

        // Completes normally: the write that triggered it must still succeed
        await(dashboardCache.invalidate());

        verify(redis).del(List.of(KEY));
    }

    @Test
    void invalidationMakesTheNextRequestRecalculate() throws Exception {
        // 1st request: cached; then a write invalidates; 2nd request: cache empty again -> PostgreSQL
        cached(Json.encode(summary));
        await(dashboardService.getSummary());
        verify(dashboardRepository, never()).loadSummary();

        when(redis.del(anyList())).thenReturn(Future.succeededFuture());
        await(dashboardCache.invalidate());
        cacheIsEmpty();
        when(redis.set(anyList())).thenReturn(Future.succeededFuture());

        await(dashboardService.getSummary());
        verify(dashboardRepository).loadSummary();
    }

    private void cached(String json) {
        Response response = mock(Response.class);
        when(response.toString()).thenReturn(json);
        when(redis.get(KEY)).thenReturn(Future.succeededFuture(response));
    }

    /** Redis answers GET of a missing key with a null response. */
    private void cacheIsEmpty() {
        when(redis.get(any())).thenReturn(Future.succeededFuture(null));
    }
}
