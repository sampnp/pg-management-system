package com.pgmanager;

import com.pgmanager.config.Database;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.pgmanager.TestFutures.await;
import static io.vertx.core.http.HttpMethod.DELETE;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.PATCH;
import static io.vertx.core.http.HttpMethod.POST;
import static io.vertx.core.http.HttpMethod.PUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end tests for /api/tenants, check-in, check-out and occupancy history. */
class TenantOccupancyApiIntegrationTest extends ApiTestBase {

    private static final String UNKNOWN_ID = UUID.randomUUID().toString();

    private static String token;

    @BeforeAll
    static void loginAsManager() throws Exception {
        token = registerAndLogin("MANAGER");
    }

    @Test
    void fullCheckInCheckOutFlow() throws Exception {
        String propertyId = createAndGetId("/api/properties",
                new JsonObject().put("name", "Flow PG").put("address", "1 Lake Road").put("city", "Hyderabad"));
        String roomId = createAndGetId("/api/properties/" + propertyId + "/rooms", new JsonObject().put("roomNumber", "201").put("capacity", 2));
        String bedId = createAndGetId("/api/rooms/" + roomId + "/beds", new JsonObject().put("bedNumber", "A"));
        String tenantId = createTenant("Sambit Behera");
        assertEquals("PENDING", getJson("/api/tenants/" + tenantId).getString("status"));

        // Check in
        HttpResponse<Buffer> checkIn = checkIn(tenantId, bedId);
        assertEquals(200, checkIn.statusCode());
        JsonObject firstStay = checkIn.bodyAsJsonObject();
        assertEquals(bedId, firstStay.getString("bedId"));
        assertEquals(roomId, firstStay.getString("roomId"));
        assertEquals(propertyId, firstStay.getString("propertyId"));
        assertNotNull(firstStay.getString("checkIn"));
        assertNull(firstStay.getString("checkOut"));

        assertEquals("OCCUPIED", getJson("/api/beds/" + bedId).getString("status"));
        assertEquals("ACTIVE", getJson("/api/tenants/" + tenantId).getString("status"));

        JsonObject currentBed = getJson("/api/tenants/" + tenantId + "/bed");
        assertEquals(tenantId, currentBed.getString("tenantId"));
        assertEquals(bedId, currentBed.getString("bedId"));
        assertEquals(1, getArray("/api/tenants/" + tenantId + "/history").size());

        // Check out
        HttpResponse<Buffer> checkOut = send(POST, "/api/tenants/" + tenantId + "/check-out", token, null);
        assertEquals(200, checkOut.statusCode());
        assertNotNull(checkOut.bodyAsJsonObject().getString("checkOut"));

        assertEquals("AVAILABLE", getJson("/api/beds/" + bedId).getString("status"));
        assertEquals("CHECKED_OUT", getJson("/api/tenants/" + tenantId).getString("status"));
        assertError(send(GET, "/api/tenants/" + tenantId + "/bed", token, null), 404, "NOT_FOUND", "Tenant is not checked in to any bed");

        JsonArray history = getArray("/api/tenants/" + tenantId + "/history");
        assertEquals(1, history.size());
        assertNotNull(history.getJsonObject(0).getString("checkIn"));
        assertNotNull(history.getJsonObject(0).getString("checkOut"));

        // Check in again: a NEW history row is created and the old one is left untouched
        HttpResponse<Buffer> secondCheckIn = checkIn(tenantId, bedId);
        assertEquals(200, secondCheckIn.statusCode());
        assertNotEquals(firstStay.getString("id"), secondCheckIn.bodyAsJsonObject().getString("id"));

        JsonArray historyAfter = getArray("/api/tenants/" + tenantId + "/history");
        assertEquals(2, historyAfter.size());
        JsonObject newest = historyAfter.getJsonObject(0);
        JsonObject oldest = historyAfter.getJsonObject(1);
        assertNull(newest.getString("checkOut"), "newest first: the current stay comes first");
        assertEquals(firstStay.getString("id"), oldest.getString("id"));
        assertEquals(history.getJsonObject(0).getString("checkOut"), oldest.getString("checkOut"), "old stay must not change");
        assertTrue(Instant.parse(newest.getString("checkIn")).isAfter(Instant.parse(oldest.getString("checkIn"))));
    }

    // ---------- tenant CRUD ----------

    @Test
    void createTenantNormalizesInputAndStartsPending() throws Exception {
        JsonObject body = new JsonObject().put("name", "  Sambit Behera ").put("phone", "+91 85998-00080")
                .put("email", " Sambit@Example.com ").put("joiningDate", "2026-10-10")
                .put("monthlyRent", 8500).put("securityDeposit", 10000);

        HttpResponse<Buffer> response = send(POST, "/api/tenants", token, body);

        assertEquals(201, response.statusCode());
        JsonObject tenant = response.bodyAsJsonObject();
        assertNotNull(tenant.getString("id"));
        assertEquals("Sambit Behera", tenant.getString("name"));
        assertEquals("+918599800080", tenant.getString("phone"));
        assertEquals("sambit@example.com", tenant.getString("email"));
        assertEquals("2026-10-10", tenant.getString("joiningDate"));
        assertEquals(8500, tenant.getNumber("monthlyRent").intValue());
        assertEquals("PENDING", tenant.getString("status"));
    }

    @Test
    void listAndGetTenants() throws Exception {
        String tenantId = createTenant("Listed Tenant");

        JsonArray tenants = getArray("/api/tenants");
        assertTrue(tenants.stream().anyMatch(t -> tenantId.equals(((JsonObject) t).getString("id"))));
        assertEquals("Listed Tenant", getJson("/api/tenants/" + tenantId).getString("name"));
    }

    @Test
    void invalidTenantInputReturns400() throws Exception {
        assertError(send(POST, "/api/tenants", token, tenantJson("Bad Rent").put("monthlyRent", -500)), 400, "BAD_REQUEST",
                "monthlyRent must be greater than 0");
        assertError(send(POST, "/api/tenants", token, tenantJson("Bad Email").put("email", "nope")), 400, "BAD_REQUEST",
                "email is not valid");
        assertError(send(POST, "/api/tenants", token, tenantJson("Bad Date").put("joiningDate", "2026-13-01")), 400, "BAD_REQUEST",
                "joiningDate must be a valid date in YYYY-MM-DD format");
    }

    @Test
    void updateChangesDetailsButIgnoresStatus() throws Exception {
        String tenantId = createTenant("Before Update");

        HttpResponse<Buffer> response = send(PUT, "/api/tenants/" + tenantId, token,
                tenantJson("After Update").put("monthlyRent", 9000).put("status", "ACTIVE"));

        assertEquals(200, response.statusCode());
        assertEquals("After Update", response.bodyAsJsonObject().getString("name"));
        assertEquals("PENDING", response.bodyAsJsonObject().getString("status"), "status only changes via check-in/check-out");
    }

    @Test
    void deleteRulesProtectOccupancyHistory() throws Exception {
        // A tenant who never stayed anywhere can be deleted
        String pending = createTenant("Never Stayed");
        assertEquals(204, send(DELETE, "/api/tenants/" + pending, token, null).statusCode());
        assertError(send(GET, "/api/tenants/" + pending, token, null), 404, "NOT_FOUND", "Tenant not found");

        // A checked-in tenant cannot
        String bedId = createBed();
        String active = createTenant("Still Here");
        checkIn(active, bedId);
        assertError(send(DELETE, "/api/tenants/" + active, token, null), 409, "CONFLICT",
                "Cannot delete a tenant who is checked in; check them out first");

        // A checked-out tenant cannot either, because their history must be kept
        send(POST, "/api/tenants/" + active + "/check-out", token, null);
        assertError(send(DELETE, "/api/tenants/" + active, token, null), 409, "CONFLICT",
                "Tenant cannot be deleted because they have occupancy, payment or maintenance history");
        assertEquals(1, getArray("/api/tenants/" + active + "/history").size());
    }

    // ---------- negative cases ----------

    @Test
    void requestsWithoutTokenReturn401() throws Exception {
        String message = "Missing or invalid Authorization header";
        assertError(send(GET, "/api/tenants", null, null), 401, "UNAUTHORIZED", message);
        assertError(send(POST, "/api/tenants", null, tenantJson("No Auth")), 401, "UNAUTHORIZED", message);
        assertError(send(POST, "/api/tenants/" + UNKNOWN_ID + "/check-in", null, new JsonObject().put("bedId", UNKNOWN_ID)),
                401, "UNAUTHORIZED", message);
        assertError(send(POST, "/api/tenants/" + UNKNOWN_ID + "/check-out", null, null), 401, "UNAUTHORIZED", message);
        assertError(send(GET, "/api/tenants/" + UNKNOWN_ID + "/history", null, null), 401, "UNAUTHORIZED", message);
    }

    @Test
    void invalidTokenReturns401() throws Exception {
        assertError(send(GET, "/api/tenants", "not.a.jwt", null), 401, "UNAUTHORIZED", "Invalid or expired token");
    }

    @Test
    void malformedIdsReturn400() throws Exception {
        assertError(send(GET, "/api/tenants/123", token, null), 400, "BAD_REQUEST", "id must be a valid UUID");
        assertError(send(POST, "/api/tenants/123/check-in", token, new JsonObject().put("bedId", UNKNOWN_ID)), 400, "BAD_REQUEST",
                "tenantId must be a valid UUID");
        assertError(checkIn(createTenant("Bad Bed Id"), "not-a-uuid"), 400, "BAD_REQUEST", "bedId must be a valid UUID");
    }

    @Test
    void unknownTenantOrBedReturns404() throws Exception {
        assertError(checkIn(UNKNOWN_ID, createBed()), 404, "NOT_FOUND", "Tenant not found");
        assertError(checkIn(createTenant("No Such Bed"), UNKNOWN_ID), 404, "NOT_FOUND", "Bed not found");
        assertError(send(POST, "/api/tenants/" + UNKNOWN_ID + "/check-out", token, null), 404, "NOT_FOUND", "Tenant not found");
        assertError(send(GET, "/api/tenants/" + UNKNOWN_ID + "/history", token, null), 404, "NOT_FOUND", "Tenant not found");
    }

    @Test
    void checkInToOccupiedBedReturns409() throws Exception {
        String bedId = createBed();
        checkIn(createTenant("First Tenant"), bedId);
        String second = createTenant("Second Tenant");

        assertError(checkIn(second, bedId), 409, "CONFLICT", "Bed is already occupied");
        assertEquals("PENDING", getJson("/api/tenants/" + second).getString("status"));
    }

    @Test
    void checkInOfAlreadyCheckedInTenantReturns409() throws Exception {
        String tenantId = createTenant("Double Booker");
        checkIn(tenantId, createBed());
        String otherBed = createBed();

        assertError(checkIn(tenantId, otherBed), 409, "CONFLICT", "Tenant is already checked in to a bed");
        assertEquals("AVAILABLE", getJson("/api/beds/" + otherBed).getString("status"));
    }

    @Test
    void checkOutWithoutActiveStayReturns409() throws Exception {
        String tenantId = createTenant("Not Checked In");
        assertError(send(POST, "/api/tenants/" + tenantId + "/check-out", token, null), 409, "CONFLICT",
                "Tenant is not checked in to any bed");

        checkIn(tenantId, createBed());
        assertEquals(200, send(POST, "/api/tenants/" + tenantId + "/check-out", token, null).statusCode());
        assertError(send(POST, "/api/tenants/" + tenantId + "/check-out", token, null), 409, "CONFLICT",
                "Tenant is not checked in to any bed");
    }

    // ---------- bed status is owned by check-in/check-out ----------

    @Test
    void bedStatusCannotContradictOccupancy() throws Exception {
        String bedId = createBed();
        String tenantId = createTenant("Status Owner");
        checkIn(tenantId, bedId);

        assertError(send(PATCH, "/api/beds/" + bedId + "/status", token, new JsonObject().put("status", "AVAILABLE")), 409, "CONFLICT",
                "Bed has a checked-in tenant; check the tenant out instead");
        assertError(send(DELETE, "/api/beds/" + bedId, token, null), 409, "CONFLICT", "Cannot delete an occupied bed");

        send(POST, "/api/tenants/" + tenantId + "/check-out", token, null);
        assertError(send(DELETE, "/api/beds/" + bedId, token, null), 409, "CONFLICT",
                "Bed cannot be deleted because it has occupancy history");
    }

    // ---------- concurrency & transactions ----------

    @Test
    void twoCheckInsRacingForTheSameBedOnlyOneWins() throws Exception {
        String bedId = createBed();
        String first = createTenant("Racer One");
        String second = createTenant("Racer Two");

        // Send both requests before waiting for either, so they really run at the same time
        Future<HttpResponse<Buffer>> requestA = checkInAsync(first, bedId);
        Future<HttpResponse<Buffer>> requestB = checkInAsync(second, bedId);
        List<Integer> statuses = List.of(await(requestA).statusCode(), await(requestB).statusCode());

        assertTrue(statuses.contains(200) && statuses.contains(409), "expected one 200 and one 409 but got " + statuses);
        int totalStays = getArray("/api/tenants/" + first + "/history").size() + getArray("/api/tenants/" + second + "/history").size();
        assertEquals(1, totalStays, "only one tenant may have a stay in the bed");
    }

    /**
     * Proves check-in is atomic. A temporary database trigger makes the LAST step (updating the tenant)
     * fail, after the history row was inserted and the bed was marked OCCUPIED. Everything must be rolled back.
     */
    @Test
    void failedCheckInRollsBackAllChanges() throws Exception {
        String bedId = createBed();
        String tenantId = createTenant("Rollback Tenant");
        Pool db = Database.createPool(vertx, databaseConfig());
        try {
            await(db.query("""
                    CREATE OR REPLACE FUNCTION fail_for_test() RETURNS trigger AS $$
                    BEGIN RAISE EXCEPTION 'simulated failure'; END;
                    $$ LANGUAGE plpgsql""").execute());
            await(db.query("""
                    CREATE TRIGGER fail_tenant_update BEFORE UPDATE ON tenants
                    FOR EACH ROW WHEN (OLD.name = 'Rollback Tenant') EXECUTE FUNCTION fail_for_test()""").execute());

            assertError(checkIn(tenantId, bedId), 500, "INTERNAL_SERVER_ERROR", "Internal server error");
        } finally {
            await(db.query("DROP TRIGGER IF EXISTS fail_tenant_update ON tenants").execute());
            await(db.close());
        }

        assertEquals("AVAILABLE", getJson("/api/beds/" + bedId).getString("status"), "bed update was rolled back");
        assertEquals("PENDING", getJson("/api/tenants/" + tenantId).getString("status"), "tenant is unchanged");
        assertEquals(0, getArray("/api/tenants/" + tenantId + "/history").size(), "history insert was rolled back");

        // With the failure removed, the same check-in works
        assertEquals(200, checkIn(tenantId, bedId).statusCode());
    }

    // ---------- helpers ----------

    private static JsonObject tenantJson(String name) {
        return new JsonObject().put("name", name).put("phone", "9876543210").put("email", "tenant@example.com")
                .put("joiningDate", "2026-10-10").put("monthlyRent", 8500).put("securityDeposit", 10000);
    }

    private static String createTenant(String name) throws Exception {
        return createAndGetId("/api/tenants", tenantJson(name));
    }

    /** Creates a property with one room and one bed, and returns the bed id. */
    private static String createBed() throws Exception {
        String propertyId = createAndGetId("/api/properties",
                new JsonObject().put("name", "Tenant Test PG").put("address", "1 Main Road").put("city", "Pune"));
        String roomId = createAndGetId("/api/properties/" + propertyId + "/rooms", new JsonObject().put("roomNumber", "101").put("capacity", 1));
        return createAndGetId("/api/rooms/" + roomId + "/beds", new JsonObject().put("bedNumber", "A"));
    }

    private static HttpResponse<Buffer> checkIn(String tenantId, String bedId) throws Exception {
        return await(checkInAsync(tenantId, bedId));
    }

    private static Future<HttpResponse<Buffer>> checkInAsync(String tenantId, String bedId) {
        return client.post("/api/tenants/" + tenantId + "/check-in")
                .putHeader("Authorization", "Bearer " + token)
                .sendJsonObject(new JsonObject().put("bedId", bedId));
    }

    private static String createAndGetId(String path, JsonObject body) throws Exception {
        HttpResponse<Buffer> response = send(POST, path, token, body);
        assertEquals(201, response.statusCode(), () -> "Setup request failed: " + response.bodyAsString());
        return response.bodyAsJsonObject().getString("id");
    }

    private static JsonObject getJson(String path) throws Exception {
        HttpResponse<Buffer> response = send(GET, path, token, null);
        assertEquals(200, response.statusCode(), () -> "GET " + path + " failed: " + response.bodyAsString());
        return response.bodyAsJsonObject();
    }

    private static JsonArray getArray(String path) throws Exception {
        HttpResponse<Buffer> response = send(GET, path, token, null);
        assertEquals(200, response.statusCode(), () -> "GET " + path + " failed: " + response.bodyAsString());
        return response.bodyAsJsonArray();
    }
}
