package com.pgmanager;

import com.pgmanager.config.AppConfig;
import com.pgmanager.config.JwtConfig;
import com.pgmanager.config.RedisConfig;
import com.pgmanager.dto.DashboardSummary;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.util.List;

import static com.pgmanager.TestFutures.await;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.PATCH;
import static io.vertx.core.http.HttpMethod.POST;
import static io.vertx.core.http.HttpMethod.PUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for GET /api/dashboard with a real PostgreSQL and a real Redis.
 * Other test classes share the same database, so data checks compare the dashboard before and after
 * this test's own changes (test classes run one after another, never at the same time).
 */
class DashboardApiIntegrationTest extends ApiTestBase {

    private static final String KEY = "dashboard:summary";

    private static String managerToken;
    private static String adminToken;
    /** Direct access to the test Redis, to look at (and tamper with) the cached value. */
    private static RedisAPI redisApi;

    @BeforeAll
    static void setUpClients() throws Exception {
        managerToken = registerAndLogin("MANAGER");
        adminToken = registerAndLogin("ADMIN");
        redisApi = RedisAPI.api(Redis.createClient(vertx, redisConfig().connectionString()));
    }

    // ---------- access ----------

    @Test
    void onlyStaffCanSeeTheDashboard() throws Exception {
        assertError(send(GET, "/api/dashboard", null, null), 401, "UNAUTHORIZED", "Missing or invalid Authorization header");
        assertError(send(GET, "/api/dashboard", tenantToken(), null), 403, "FORBIDDEN", "Insufficient permissions");
        assertEquals(200, send(GET, "/api/dashboard", managerToken, null).statusCode());
        assertEquals(200, send(GET, "/api/dashboard", adminToken, null).statusCode());
    }

    // ---------- numbers ----------

    @Test
    void dashboardCountsMatchTheData() throws Exception {
        DashboardSummary before = dashboard();

        // 1 property, 2 rooms, 3 beds
        String propertyId = createAndGetId("/api/properties", new JsonObject().put("name", "Dashboard PG").put("address", "9 Hill Road").put("city", "Mysuru"));
        String room1 = createAndGetId("/api/properties/" + propertyId + "/rooms", new JsonObject().put("roomNumber", "1").put("capacity", 2));
        String room2 = createAndGetId("/api/properties/" + propertyId + "/rooms", new JsonObject().put("roomNumber", "2").put("capacity", 1));
        String bedA = createAndGetId("/api/rooms/" + room1 + "/beds", new JsonObject().put("bedNumber", "A"));
        String bedB = createAndGetId("/api/rooms/" + room1 + "/beds", new JsonObject().put("bedNumber", "B"));
        createAndGetId("/api/rooms/" + room2 + "/beds", new JsonObject().put("bedNumber", "A"));

        // 3 tenants: one stays checked in, one checks in and out again, one never checks in
        String active = createTenant("Active Tenant");
        String checkedOut = createTenant("Leaving Tenant");
        createTenant("Pending Tenant");
        checkIn(active, bedA);
        checkIn(checkedOut, bedB);
        assertEquals(200, send(POST, "/api/tenants/" + checkedOut + "/check-out", managerToken, null).statusCode());

        // Payments: 2 PAID (8000 + 4500.50), 1 PENDING (3000)
        createAndGetId("/api/payments", payment(active, "8000", "PAID"));
        createAndGetId("/api/payments", payment(checkedOut, "4500.50", "PAID"));
        createAndGetId("/api/payments", payment(active, "3000", "PENDING"));

        // Maintenance: urgent OPEN, IN_PROGRESS, RESOLVED, and an URGENT one that is CLOSED (not counted as urgent)
        createIssue(active, "URGENT");
        changeStatus(createIssue(active, "HIGH"), "IN_PROGRESS");
        changeStatus(createIssue(active, "LOW"), "RESOLVED");
        String closed = createIssue(active, "URGENT");
        changeStatus(closed, "RESOLVED");
        changeStatus(closed, "CLOSED");

        DashboardSummary after = dashboard();

        assertEquals(before.properties() + 1, after.properties());
        assertEquals(before.rooms() + 2, after.rooms());
        assertEquals(before.beds().total() + 3, after.beds().total());
        assertEquals(before.beds().occupied() + 1, after.beds().occupied());
        assertEquals(before.beds().available() + 2, after.beds().available());
        assertEquals(before.tenants().pending() + 1, after.tenants().pending());
        assertEquals(before.tenants().active() + 1, after.tenants().active());
        assertEquals(before.tenants().checkedOut() + 1, after.tenants().checkedOut());
        assertEquals(before.payments().paidCount() + 2, after.payments().paidCount());
        assertEquals(before.payments().pendingCount() + 1, after.payments().pendingCount());
        assertAmount(before.payments().paidAmount().add(new BigDecimal("12500.50")), after.payments().paidAmount());
        assertAmount(before.payments().pendingAmount().add(new BigDecimal("3000.00")), after.payments().pendingAmount());
        assertEquals(before.maintenance().open() + 1, after.maintenance().open());
        assertEquals(before.maintenance().inProgress() + 1, after.maintenance().inProgress());
        assertEquals(before.maintenance().resolved() + 1, after.maintenance().resolved());
        assertEquals(before.maintenance().closed() + 1, after.maintenance().closed());
        assertEquals(before.maintenance().urgent() + 1, after.maintenance().urgent());
        // Totals always add up
        assertEquals(after.beds().total(), after.beds().available() + after.beds().occupied());
    }

    // ---------- cache ----------

    @Test
    void secondRequestIsServedFromRedisUntilACheckInClearsIt() throws Exception {
        String bedId = createBed();
        String tenantId = createTenant("Cache Tenant");
        await(redisApi.del(List.of(KEY)));

        // 1st request: calculated in PostgreSQL and stored in Redis with the 60 second TTL
        HttpResponse<Buffer> first = send(GET, "/api/dashboard", managerToken, null);
        assertEquals(200, first.statusCode());
        Response cached = await(redisApi.get(KEY));
        assertNotNull(cached, "first request should populate the cache");
        assertEquals(first.bodyAsJsonObject(), new JsonObject(cached.toString()));
        long ttl = await(redisApi.ttl(KEY)).toLong();
        assertTrue(ttl > 0 && ttl <= 60, "TTL was " + ttl);

        // Prove the 2nd request comes from Redis: change the cached copy and see the change in the response
        JsonObject tampered = first.bodyAsJsonObject().put("properties", 999_999);
        await(redisApi.set(List.of(KEY, tampered.encode(), "EX", "60")));
        assertEquals(999_999, send(GET, "/api/dashboard", managerToken, null).bodyAsJsonObject().getLong("properties"));

        // A check-in changes the counts, so it must clear the cache...
        checkIn(tenantId, bedId);
        assertNull(await(redisApi.get(KEY)), "check-in should clear the cached dashboard");

        // ...and the next request recalculates from PostgreSQL
        DashboardSummary fresh = dashboard();
        DashboardSummary original = Json.decodeValue(first.bodyAsString(), DashboardSummary.class);
        assertNotEquals(999_999, fresh.properties());
        assertEquals(original.beds().occupied() + 1, fresh.beds().occupied());
        assertEquals(original.tenants().active() + 1, fresh.tenants().active());
    }

    @Test
    void newPaymentClearsTheCacheAndShowsUpImmediately() throws Exception {
        String tenantId = createTenant("Payment Cache Tenant");
        DashboardSummary before = dashboard();
        assertNotNull(await(redisApi.get(KEY)));

        createAndGetId("/api/payments", payment(tenantId, "6500", "PAID"));

        assertNull(await(redisApi.get(KEY)), "a new payment should clear the cached dashboard");
        DashboardSummary after = dashboard();
        assertEquals(before.payments().paidCount() + 1, after.payments().paidCount());
        assertAmount(before.payments().paidAmount().add(new BigDecimal("6500")), after.payments().paidAmount());
    }

    @Test
    void readsAndChangesTheDashboardDoesNotCountKeepTheCache() throws Exception {
        String propertyId = createAndGetId("/api/properties", new JsonObject().put("name", "Keep PG").put("address", "1 Road").put("city", "Goa"));
        String tenantId = createTenant("Keep Tenant");
        checkIn(tenantId, createBed());
        String issueId = createIssue(tenantId, "LOW");
        String managerId = send(GET, "/api/auth/me", managerToken, null).bodyAsJsonObject().getString("id");
        dashboard();
        assertNotNull(await(redisApi.get(KEY)));

        assertEquals(200, send(GET, "/api/tenants", managerToken, null).statusCode());
        assertEquals(200, send(GET, "/api/maintenance", managerToken, null).statusCode());
        assertEquals(200, send(PUT, "/api/properties/" + propertyId, managerToken,
                new JsonObject().put("name", "Renamed PG").put("address", "1 Road").put("city", "Goa")).statusCode());
        assertEquals(200, send(PATCH, "/api/maintenance/" + issueId + "/assign", managerToken,
                new JsonObject().put("assignedTo", managerId)).statusCode());

        assertNotNull(await(redisApi.get(KEY)), "reads, renames and assignments should not clear the cache");
    }

    @Test
    void corruptedCacheIsIgnoredAndReplaced() throws Exception {
        await(redisApi.set(List.of(KEY, "{ this is not json", "EX", "60")));

        HttpResponse<Buffer> response = send(GET, "/api/dashboard", managerToken, null);

        assertEquals(200, response.statusCode(), response::bodyAsString);
        assertEquals(response.bodyAsJsonObject(), new JsonObject(await(redisApi.get(KEY)).toString()));
    }

    /** A second copy of the application whose Redis address points at a port where nothing is listening. */
    @Test
    void dashboardAndWritesStillWorkWhenRedisIsUnavailable() throws Exception {
        int deadRedisPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadRedisPort = socket.getLocalPort();
        }
        int appPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            appPort = socket.getLocalPort();
        }
        AppConfig config = new AppConfig(appPort, databaseConfig(),
                new JwtConfig("integration-test-secret-at-least-32-chars", 3600),
                new RedisConfig("localhost", deadRedisPort, 60));
        String deploymentId = await(vertx.deployVerticle(new MainVerticle(config)));
        WebClient noRedisClient = WebClient.create(vertx, new WebClientOptions().setDefaultHost("localhost").setDefaultPort(appPort));
        try {
            HttpResponse<Buffer> dashboard = await(noRedisClient.get("/api/dashboard").putHeader("Authorization", "Bearer " + managerToken).send());
            assertEquals(200, dashboard.statusCode(), dashboard::bodyAsString);
            long properties = dashboard.bodyAsJsonObject().getLong("properties");

            // A write still succeeds although clearing the cache fails
            HttpResponse<Buffer> created = await(noRedisClient.post("/api/properties").putHeader("Authorization", "Bearer " + managerToken)
                    .sendJsonObject(new JsonObject().put("name", "No Redis PG").put("address", "2 Road").put("city", "Agra")));
            assertEquals(201, created.statusCode(), created::bodyAsString);

            // Without a cache every request reads PostgreSQL, so the new property shows up straight away
            HttpResponse<Buffer> again = await(noRedisClient.get("/api/dashboard").putHeader("Authorization", "Bearer " + managerToken).send());
            assertEquals(200, again.statusCode());
            assertEquals(properties + 1, again.bodyAsJsonObject().getLong("properties"));
        } finally {
            noRedisClient.close();
            await(vertx.undeploy(deploymentId));
            // That copy could not clear the shared cache, so clear it here for the other tests
            await(redisApi.del(List.of(KEY)));
        }
    }

    // ---------- helpers ----------

    private static DashboardSummary dashboard() throws Exception {
        HttpResponse<Buffer> response = send(GET, "/api/dashboard", managerToken, null);
        assertEquals(200, response.statusCode(), response::bodyAsString);
        // Decoded with the application's own Jackson setup, so amounts stay exact BigDecimals
        return Json.decodeValue(response.bodyAsString(), DashboardSummary.class);
    }

    private static void assertAmount(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }

    private static String tenantToken() throws Exception {
        String tenantId = createTenant("Dashboard Tenant User");
        String email = uniqueEmail();
        createAndGetId("/api/tenants/" + tenantId + "/account", new JsonObject().put("email", email).put("password", "password123"));
        return login(email, "password123").bodyAsJsonObject().getString("token");
    }

    private static String createTenant(String name) throws Exception {
        return createAndGetId("/api/tenants", new JsonObject().put("name", name).put("phone", "9876543210")
                .put("joiningDate", "2026-08-01").put("monthlyRent", 8000).put("securityDeposit", 10000));
    }

    private static String createBed() throws Exception {
        String propertyId = createAndGetId("/api/properties",
                new JsonObject().put("name", "Dashboard Test PG").put("address", "3 Station Road").put("city", "Nagpur"));
        String roomId = createAndGetId("/api/properties/" + propertyId + "/rooms", new JsonObject().put("roomNumber", "101").put("capacity", 1));
        return createAndGetId("/api/rooms/" + roomId + "/beds", new JsonObject().put("bedNumber", "A"));
    }

    private static void checkIn(String tenantId, String bedId) throws Exception {
        HttpResponse<Buffer> response = send(POST, "/api/tenants/" + tenantId + "/check-in", managerToken, new JsonObject().put("bedId", bedId));
        assertEquals(200, response.statusCode(), response::bodyAsString);
    }

    private static JsonObject payment(String tenantId, String amount, String status) {
        JsonObject body = new JsonObject().put("tenantId", tenantId).put("amount", new BigDecimal(amount))
                .put("rentMonth", "2026-10").put("status", status);
        return "PAID".equals(status) ? body.put("paymentMethod", "UPI").put("paymentDate", "2026-10-05") : body;
    }

    private static String createIssue(String tenantId, String priority) throws Exception {
        return createAndGetId("/api/maintenance", new JsonObject().put("tenantId", tenantId).put("title", "Issue " + priority)
                .put("description", "Reported for the dashboard test").put("category", "OTHER").put("priority", priority));
    }

    private static void changeStatus(String issueId, String status) throws Exception {
        HttpResponse<Buffer> response = send(PATCH, "/api/maintenance/" + issueId + "/status", managerToken, new JsonObject().put("status", status));
        assertEquals(200, response.statusCode(), response::bodyAsString);
    }

    private static String createAndGetId(String path, JsonObject body) throws Exception {
        HttpResponse<Buffer> response = send(POST, path, managerToken, body);
        assertEquals(201, response.statusCode(), () -> "Setup request failed: " + response.bodyAsString());
        return response.bodyAsJsonObject().getString("id");
    }
}
