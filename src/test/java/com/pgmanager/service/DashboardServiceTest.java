package com.pgmanager.service;

import com.pgmanager.config.JsonConfig;
import com.pgmanager.dto.DashboardSummary;
import com.pgmanager.dto.PropertyDashboard;
import com.pgmanager.exception.NotFoundException;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.pgmanager.TestFutures.await;
import static com.pgmanager.TestFutures.awaitFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        // SET dashboard:summary <json> EX 60 NX - only if no write cleared the key meanwhile
        verify(redis).set(List.of(KEY, Json.encode(summary), "EX", "60", "NX"));
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

        verify(redis).set(List.of(KEY, Json.encode(summary), "EX", "15", "NX"));
    }

    @Test
    void redisGetFailureFallsBackToPostgres() throws Exception {
        when(redis.get(KEY)).thenReturn(Future.failedFuture(new ConnectException("Connection refused")));
        when(redis.set(anyList())).thenReturn(Future.succeededFuture());

        assertSame(summary, await(dashboardService.getSummary()));
        verify(dashboardRepository).loadSummary();
        // Redis is not usable, so there is no point trying to write to it
        verify(redis, never()).set(anyList());
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
    void invalidateMarksTheKeyClearedForAFewSeconds() throws Exception {
        when(redis.set(anyList())).thenReturn(Future.succeededFuture());

        await(dashboardCache.invalidate(Set.of()));

        verify(redis).set(List.of(KEY, "cleared", "EX", "5"));
    }

    @Test
    void justClearedDashboardIsRecalculatedButNotCached() throws Exception {
        // A request that started before a write may hold older numbers; while the key says "cleared",
        // nobody caches, so those older numbers can't get back into the cache
        cached("cleared");

        assertSame(summary, await(dashboardService.getSummary()));
        verify(dashboardRepository).loadSummary();
        verify(redis, never()).set(anyList());
    }

    @Test
    void refusedSetNxStillReturnsTheFreshNumbers() throws Exception {
        cacheIsEmpty();
        // SET ... NX answers null when the key was filled (e.g. cleared by a write) in the meantime
        when(redis.set(anyList())).thenReturn(Future.succeededFuture(null));

        assertSame(summary, await(dashboardService.getSummary()));
    }

    @Test
    void invalidateNeverFailsEvenWhenRedisIsDown() throws Exception {
        when(redis.set(anyList())).thenReturn(Future.failedFuture(new ConnectException("Connection refused")));

        // Completes normally: the write that triggered it must still succeed
        await(dashboardCache.invalidate(Set.of()));

        verify(redis).set(List.of(KEY, "cleared", "EX", "5"));
    }

    @Test
    void failedClearIsRepeatedBeforeTheCacheIsUsedAgain() throws Exception {
        List<String> clear = List.of(KEY, "cleared", "EX", "5");
        when(redis.set(anyList())).thenReturn(Future.failedFuture(new ConnectException("Connection refused")));
        await(dashboardCache.invalidate(Set.of()));

        // Still failing: the (possibly old) cached value is not even read
        assertSame(summary, await(dashboardService.getSummary()));
        verify(redis, never()).get(any());

        // Redis is back: the clear is repeated first, then the cache is used as normal
        when(redis.set(anyList())).thenReturn(Future.succeededFuture());
        cacheIsEmpty();
        assertSame(summary, await(dashboardService.getSummary()));
        verify(redis, times(3)).set(clear);
        verify(redis).get(KEY);

        // Done: no more repeats
        await(dashboardService.getSummary());
        verify(redis, times(3)).set(clear);
    }

    // ---------- property dashboard ----------

    @Test
    void propertyDashboardIsCachedUnderItsOwnKey() throws Exception {
        UUID propertyId = UUID.randomUUID();
        PropertyDashboard property = propertyDashboard(propertyId);
        String key = "dashboard:property:" + propertyId;
        when(redis.get(key)).thenReturn(Future.succeededFuture(null));
        when(redis.set(anyList())).thenReturn(Future.succeededFuture());
        when(dashboardRepository.loadPropertySummary(propertyId)).thenReturn(Future.succeededFuture(Optional.of(property)));

        assertEquals(property, await(dashboardService.getPropertySummary(propertyId)));
        verify(redis).set(List.of(key, Json.encode(property), "EX", "60", "NX"));

        // Next request: served from that key
        Response response = mock(Response.class);
        when(response.toString()).thenReturn(Json.encode(property));
        when(redis.get(key)).thenReturn(Future.succeededFuture(response));
        assertEquals(property, await(dashboardService.getPropertySummary(propertyId)));
        verify(dashboardRepository).loadPropertySummary(propertyId);
    }

    @Test
    void unknownPropertyIs404AndNothingIsCached() throws Exception {
        UUID propertyId = UUID.randomUUID();
        when(redis.get(any())).thenReturn(Future.succeededFuture(null));
        when(dashboardRepository.loadPropertySummary(propertyId)).thenReturn(Future.succeededFuture(Optional.empty()));

        Throwable error = awaitFailure(dashboardService.getPropertySummary(propertyId));

        assertInstanceOf(NotFoundException.class, error);
        assertEquals("Property not found", error.getMessage());
        verify(redis, never()).set(anyList());
    }

    @Test
    void cachedPgWideJsonIsNotAcceptedAsAPropertyDashboard() throws Exception {
        UUID propertyId = UUID.randomUUID();
        Response response = mock(Response.class);
        when(response.toString()).thenReturn(Json.encode(summary));
        when(redis.get("dashboard:property:" + propertyId)).thenReturn(Future.succeededFuture(response));
        when(redis.set(anyList())).thenReturn(Future.succeededFuture());
        when(dashboardRepository.loadPropertySummary(propertyId)).thenReturn(Future.succeededFuture(Optional.of(propertyDashboard(propertyId))));

        assertEquals(propertyId, await(dashboardService.getPropertySummary(propertyId)).propertyId());
        verify(dashboardRepository).loadPropertySummary(propertyId);
    }

    @Test
    void invalidateClearsThePgWideKeyAndOnlyTheChangedProperties() throws Exception {
        UUID changed = UUID.randomUUID();
        when(redis.set(anyList())).thenReturn(Future.succeededFuture());

        await(dashboardCache.invalidate(Set.of(changed)));

        verify(redis).set(List.of(KEY, "cleared", "EX", "5"));
        verify(redis).set(List.of("dashboard:property:" + changed, "cleared", "EX", "5"));
        verify(redis, times(2)).set(anyList());
    }

    private static PropertyDashboard propertyDashboard(UUID propertyId) {
        return new PropertyDashboard(propertyId, 4,
                new DashboardSummary.Beds(8, 3, 5),
                new PropertyDashboard.Tenants(5, 2),
                new DashboardSummary.Payments(10, 2, new BigDecimal("80000.00"), new BigDecimal("16000.00")),
                new DashboardSummary.Maintenance(1, 1, 3, 0, 1),
                Instant.parse("2026-10-09T10:15:30Z"));
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
