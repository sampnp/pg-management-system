package com.pgmanager;

import com.pgmanager.dto.PropertyDashboard;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.pgmanager.TestFutures.await;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.PATCH;
import static io.vertx.core.http.HttpMethod.POST;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** GET /api/properties/:propertyId/dashboard with a real PostgreSQL and Redis. Each test builds its own properties. */
class PropertyDashboardApiIntegrationTest extends ApiTestBase {

    /** The month the stays below happen in (check-in uses the database clock, which is UTC in the container). */
    private static final String THIS_MONTH = YearMonth.now(ZoneOffset.UTC).toString();

    private static String managerToken;
    private static String adminToken;

    @BeforeAll
    static void setUpClients() throws Exception {
        managerToken = registerAndLogin("MANAGER");
        adminToken = registerAndLogin("ADMIN");
    }

    @Test
    void onlyStaffCanSeeAPropertyDashboard() throws Exception {
        String propertyId = createProperty("Access PG");
        String path = "/api/properties/" + propertyId + "/dashboard";

        assertError(send(GET, path, null, null), 401, "UNAUTHORIZED", "Missing or invalid Authorization header");
        assertError(send(GET, path, tenantToken(), null), 403, "FORBIDDEN", "Insufficient permissions");
        assertEquals(200, send(GET, path, managerToken, null).statusCode());
        assertEquals(200, send(GET, path, adminToken, null).statusCode());
        assertError(send(GET, "/api/properties/" + UUID.randomUUID() + "/dashboard", managerToken, null),
                404, "NOT_FOUND", "Property not found");
        assertError(send(GET, "/api/properties/not-a-uuid/dashboard", managerToken, null),
                400, "BAD_REQUEST", "propertyId must be a valid UUID");
    }

    @Test
    void countsOnlyTheDataOfThatProperty() throws Exception {
        // Property A: 2 rooms, 3 beds. Property B: 1 room, 1 bed.
        String propertyA = createProperty("Dashboard A");
        String roomA1 = createRoom(propertyA, "1", 2);
        String roomA2 = createRoom(propertyA, "2", 1);
        String bedA1 = createBed(roomA1, "A");
        String bedA2 = createBed(roomA1, "B");
        createBed(roomA2, "A");
        String propertyB = createProperty("Dashboard B");
        String bedB1 = createBed(createRoom(propertyB, "1", 1), "A");

        // A: one tenant living there, one who checked out. B: one tenant. Plus one tenant who never checked in.
        String livesInA = createTenant("Lives in A");
        String leftA = createTenant("Left A");
        String livesInB = createTenant("Lives in B");
        String neverCheckedIn = createTenant("Never checked in");
        checkIn(livesInA, bedA1);
        checkIn(leftA, bedA2);
        assertEquals(200, send(POST, "/api/tenants/" + leftA + "/check-out", managerToken, null).statusCode());
        checkIn(livesInB, bedB1);

        // Payments count for the property where the tenant stayed during that rent month
        createPayment(livesInA, THIS_MONTH, "8000", "PAID");
        createPayment(leftA, THIS_MONTH, "5000.50", "PAID");
        createPayment(livesInB, THIS_MONTH, "7000", "PENDING");
        createPayment(livesInA, "2020-01", "999", "PAID");          // before the stay: no property
        createPayment(neverCheckedIn, THIS_MONTH, "4000", "PAID");  // never stayed anywhere: no property

        // Maintenance: A has an urgent OPEN issue and a RESOLVED one, B has one IN_PROGRESS
        createIssue(livesInA, "URGENT");
        changeStatus(createIssue(livesInA, "LOW"), "RESOLVED");
        changeStatus(createIssue(livesInB, "HIGH"), "IN_PROGRESS");

        PropertyDashboard a = propertyDashboard(propertyA);
        assertEquals(UUID.fromString(propertyA), a.propertyId());
        assertEquals(2, a.rooms());
        assertEquals(3, a.beds().total());
        assertEquals(2, a.beds().available());
        assertEquals(1, a.beds().occupied());
        assertEquals(1, a.tenants().active());
        assertEquals(1, a.tenants().checkedOut());
        assertEquals(2, a.payments().paidCount());
        assertEquals(0, a.payments().pendingCount());
        assertAmount("13000.50", a.payments().paidAmount());
        assertAmount("0", a.payments().pendingAmount());
        assertEquals(1, a.maintenance().open());
        assertEquals(0, a.maintenance().inProgress());
        assertEquals(1, a.maintenance().resolved());
        assertEquals(1, a.maintenance().urgent());

        PropertyDashboard b = propertyDashboard(propertyB);
        assertEquals(1, b.rooms());
        assertEquals(1, b.beds().total());
        assertEquals(0, b.beds().available());
        assertEquals(1, b.beds().occupied());
        assertEquals(1, b.tenants().active());
        assertEquals(0, b.tenants().checkedOut());
        assertEquals(0, b.payments().paidCount());
        assertEquals(1, b.payments().pendingCount());
        assertAmount("7000", b.payments().pendingAmount());
        assertEquals(1, b.maintenance().inProgress());
        assertEquals(0, b.maintenance().open());
        assertEquals(0, b.maintenance().urgent());

        // An empty property has all zeros
        PropertyDashboard empty = propertyDashboard(createProperty("Empty PG"));
        assertEquals(0, empty.rooms());
        assertEquals(0, empty.beds().total());
        assertEquals(0, empty.tenants().active());
        assertAmount("0", empty.payments().paidAmount());
        assertEquals(0, empty.maintenance().open());
    }

    @Test
    void eachPropertyHasItsOwnCacheKeyAndOnlyChangedPropertiesAreCleared() throws Exception {
        String propertyA = createProperty("Cache A");
        String bedA = createBed(createRoom(propertyA, "1", 2), "A");
        String propertyB = createProperty("Cache B");
        String bedB = createBed(createRoom(propertyB, "1", 1), "A");
        String keyA = "dashboard:property:" + propertyA;
        String keyB = "dashboard:property:" + propertyB;

        String tenantInA = createTenant("Cache tenant A");
        String tenantInB = createTenant("Cache tenant B");
        // Creating rooms and beds marked the keys "cleared" for 5 seconds; start from empty keys instead of waiting
        await(redisApi.del(List.of(keyA, keyB)));

        PropertyDashboard before = propertyDashboard(propertyA);
        propertyDashboard(propertyB);
        assertEquals(new JsonObject(Json.encode(before)), new JsonObject(await(redisApi.get(keyA)).toString()));
        assertCachedDashboard(keyB);

        // A check-in in property B clears B (and the PG-wide dashboard) but leaves A's cached dashboard alone
        checkIn(tenantInB, bedB);
        assertCleared(keyB);
        assertEquals(new JsonObject(Json.encode(before)), new JsonObject(await(redisApi.get(keyA)).toString()));
        assertEquals(1, propertyDashboard(propertyB).tenants().active());

        // A check-in in property A clears A, and the new numbers show up straight away
        checkIn(tenantInA, bedA);
        assertCleared(keyA);
        assertEquals(1, propertyDashboard(propertyA).beds().occupied());

        // So does a payment of a tenant staying in A
        await(redisApi.del(List.of(keyA)));
        propertyDashboard(propertyA);
        assertCachedDashboard(keyA);
        createPayment(tenantInA, THIS_MONTH, "6000", "PAID");
        assertCleared(keyA);
        assertAmount("6000", propertyDashboard(propertyA).payments().paidAmount());
    }

    // ---------- helpers ----------

    private static PropertyDashboard propertyDashboard(String propertyId) throws Exception {
        HttpResponse<Buffer> response = send(GET, "/api/properties/" + propertyId + "/dashboard", managerToken, null);
        assertEquals(200, response.statusCode(), response::bodyAsString);
        return Json.decodeValue(response.bodyAsString(), PropertyDashboard.class);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }

    private static String tenantToken() throws Exception {
        String tenantId = createTenant("Property Dashboard Tenant User");
        String email = uniqueEmail();
        createAndGetId("/api/tenants/" + tenantId + "/account", new JsonObject().put("email", email).put("password", "password123"));
        return login(email, "password123").bodyAsJsonObject().getString("token");
    }

    private static String createProperty(String name) throws Exception {
        return createAndGetId("/api/properties", new JsonObject().put("name", name).put("address", "7 Lake Road").put("city", "Udaipur"));
    }

    private static String createRoom(String propertyId, String number, int capacity) throws Exception {
        return createAndGetId("/api/properties/" + propertyId + "/rooms", new JsonObject().put("roomNumber", number).put("capacity", capacity));
    }

    private static String createBed(String roomId, String number) throws Exception {
        return createAndGetId("/api/rooms/" + roomId + "/beds", new JsonObject().put("bedNumber", number));
    }

    private static String createTenant(String name) throws Exception {
        return createAndGetId("/api/tenants", new JsonObject().put("name", name).put("phone", "9876543210")
                .put("joiningDate", "2026-01-01").put("monthlyRent", 8000).put("securityDeposit", 0));
    }

    private static void checkIn(String tenantId, String bedId) throws Exception {
        HttpResponse<Buffer> response = send(POST, "/api/tenants/" + tenantId + "/check-in", managerToken, new JsonObject().put("bedId", bedId));
        assertEquals(200, response.statusCode(), response::bodyAsString);
    }

    private static void createPayment(String tenantId, String month, String amount, String status) throws Exception {
        JsonObject body = new JsonObject().put("tenantId", tenantId).put("amount", new BigDecimal(amount))
                .put("rentMonth", month).put("status", status);
        if ("PAID".equals(status)) {
            body.put("paymentMethod", "UPI").put("paymentDate", month + "-05");
        }
        createAndGetId("/api/payments", body);
    }

    private static String createIssue(String tenantId, String priority) throws Exception {
        return createAndGetId("/api/maintenance", new JsonObject().put("tenantId", tenantId).put("title", "Issue " + priority)
                .put("description", "Property dashboard test").put("category", "OTHER").put("priority", priority));
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
