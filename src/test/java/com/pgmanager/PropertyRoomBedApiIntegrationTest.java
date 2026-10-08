package com.pgmanager;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.vertx.core.http.HttpMethod.DELETE;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.PATCH;
import static io.vertx.core.http.HttpMethod.POST;
import static io.vertx.core.http.HttpMethod.PUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end tests for /api/properties, /api/rooms and /api/beds. */
class PropertyRoomBedApiIntegrationTest extends ApiTestBase {

    private static final String UNKNOWN_ID = UUID.randomUUID().toString();

    private static String token;

    @BeforeAll
    static void loginAsManager() throws Exception {
        token = registerAndLogin("MANAGER");
    }

    @Test
    void fullFlowFromLoginToBedStatusChange() throws Exception {
        String adminToken = registerAndLogin("ADMIN");

        HttpResponse<Buffer> propertyResponse = send(POST, "/api/properties", adminToken, propertyJson("Sunrise PG"));
        assertEquals(201, propertyResponse.statusCode());
        String propertyId = propertyResponse.bodyAsJsonObject().getString("id");

        HttpResponse<Buffer> roomResponse = send(POST, "/api/properties/" + propertyId + "/rooms", adminToken, roomJson("101", 3));
        assertEquals(201, roomResponse.statusCode());
        assertEquals(propertyId, roomResponse.bodyAsJsonObject().getString("propertyId"));
        String roomId = roomResponse.bodyAsJsonObject().getString("id");

        HttpResponse<Buffer> bedResponse = send(POST, "/api/rooms/" + roomId + "/beds", adminToken, bedJson("A"));
        assertEquals(201, bedResponse.statusCode());
        assertEquals("AVAILABLE", bedResponse.bodyAsJsonObject().getString("status"));
        String bedId = bedResponse.bodyAsJsonObject().getString("id");

        JsonArray properties = send(GET, "/api/properties", adminToken, null).bodyAsJsonArray();
        assertTrue(properties.stream().anyMatch(p -> propertyId.equals(((JsonObject) p).getString("id"))));

        JsonArray rooms = send(GET, "/api/properties/" + propertyId + "/rooms", adminToken, null).bodyAsJsonArray();
        assertEquals(1, rooms.size());
        assertEquals("101", rooms.getJsonObject(0).getString("roomNumber"));

        JsonArray beds = send(GET, "/api/rooms/" + roomId + "/beds", adminToken, null).bodyAsJsonArray();
        assertEquals(1, beds.size());
        assertEquals("A", beds.getJsonObject(0).getString("bedNumber"));

        // Since Phase 4 a bed only becomes OCCUPIED through tenant check-in, not by setting the status by hand
        assertError(send(PATCH, "/api/beds/" + bedId + "/status", adminToken, statusJson("OCCUPIED")), 409, "CONFLICT",
                "A bed can only become OCCUPIED by checking a tenant in");
        assertEquals("AVAILABLE", send(GET, "/api/beds/" + bedId, adminToken, null).bodyAsJsonObject().getString("status"));

        HttpResponse<Buffer> available = send(PATCH, "/api/beds/" + bedId + "/status", adminToken, statusJson("available"));
        assertEquals(200, available.statusCode());
        assertEquals("AVAILABLE", available.bodyAsJsonObject().getString("status"));
    }

    // ---------- authentication ----------

    @Test
    void requestsWithoutTokenReturn401() throws Exception {
        String message = "Missing or invalid Authorization header";
        assertError(send(GET, "/api/properties", null, null), 401, "UNAUTHORIZED", message);
        assertError(send(POST, "/api/properties", null, propertyJson("No Auth PG")), 401, "UNAUTHORIZED", message);
        assertError(send(GET, "/api/rooms/" + UNKNOWN_ID, null, null), 401, "UNAUTHORIZED", message);
        assertError(send(POST, "/api/rooms/" + UNKNOWN_ID + "/beds", null, bedJson("A")), 401, "UNAUTHORIZED", message);
        assertError(send(PATCH, "/api/beds/" + UNKNOWN_ID + "/status", null, statusJson("OCCUPIED")), 401, "UNAUTHORIZED", message);
    }

    @Test
    void requestWithInvalidTokenReturns401() throws Exception {
        assertError(send(GET, "/api/properties", "not.a.jwt", null), 401, "UNAUTHORIZED", "Invalid or expired token");
    }

    // ---------- properties ----------

    @Test
    void createPropertyTrimsInputAndReturns201() throws Exception {
        JsonObject body = new JsonObject().put("name", "  Sunrise PG  ").put("address", " 123 Main Road ").put("city", " Hyderabad ");

        HttpResponse<Buffer> response = send(POST, "/api/properties", token, body);

        assertEquals(201, response.statusCode());
        JsonObject property = response.bodyAsJsonObject();
        assertNotNull(property.getString("id"));
        assertEquals("Sunrise PG", property.getString("name"));
        assertEquals("123 Main Road", property.getString("address"));
        assertEquals("Hyderabad", property.getString("city"));
        assertNotNull(property.getString("createdAt"));
    }

    @Test
    void createPropertyWithBlankNameReturns400() throws Exception {
        JsonObject body = new JsonObject().put("name", "   ").put("address", "123 Main Road").put("city", "Hyderabad");

        assertError(send(POST, "/api/properties", token, body), 400, "BAD_REQUEST", "name is required");
    }

    @Test
    void getPropertyById() throws Exception {
        String id = createProperty("Lookup PG");

        HttpResponse<Buffer> response = send(GET, "/api/properties/" + id, token, null);

        assertEquals(200, response.statusCode());
        assertEquals("Lookup PG", response.bodyAsJsonObject().getString("name"));
    }

    @Test
    void getUnknownPropertyReturns404() throws Exception {
        assertError(send(GET, "/api/properties/" + UNKNOWN_ID, token, null), 404, "NOT_FOUND", "Property not found");
    }

    @Test
    void malformedIdReturns400() throws Exception {
        assertError(send(GET, "/api/properties/123", token, null), 400, "BAD_REQUEST", "id must be a valid UUID");
    }

    @Test
    void updateProperty() throws Exception {
        String id = createProperty("Old Name PG");
        JsonObject body = new JsonObject().put("name", "Sunrise Premium PG").put("address", "456 Main Road").put("city", "Hyderabad");

        HttpResponse<Buffer> response = send(PUT, "/api/properties/" + id, token, body);

        assertEquals(200, response.statusCode());
        assertEquals("Sunrise Premium PG", response.bodyAsJsonObject().getString("name"));
        assertEquals("456 Main Road", send(GET, "/api/properties/" + id, token, null).bodyAsJsonObject().getString("address"));
    }

    @Test
    void updateUnknownPropertyReturns404() throws Exception {
        assertError(send(PUT, "/api/properties/" + UNKNOWN_ID, token, propertyJson("Ghost PG")), 404, "NOT_FOUND", "Property not found");
    }

    @Test
    void deletePropertyReturns204AndRemovesIt() throws Exception {
        String id = createProperty("Short Lived PG");

        HttpResponse<Buffer> response = send(DELETE, "/api/properties/" + id, token, null);

        assertEquals(204, response.statusCode());
        assertError(send(GET, "/api/properties/" + id, token, null), 404, "NOT_FOUND", "Property not found");
    }

    @Test
    void deletePropertyWithRoomsReturns409() throws Exception {
        String propertyId = createProperty("Busy PG");
        createRoom(propertyId, "101", 2);

        assertError(send(DELETE, "/api/properties/" + propertyId, token, null), 409, "CONFLICT",
                "Property cannot be deleted while it still has rooms or other related records");
    }

    // ---------- rooms ----------

    @Test
    void createRoomForUnknownPropertyReturns404() throws Exception {
        assertError(send(POST, "/api/properties/" + UNKNOWN_ID + "/rooms", token, roomJson("101", 2)), 404, "NOT_FOUND", "Property not found");
    }

    @Test
    void listRoomsForUnknownPropertyReturns404() throws Exception {
        assertError(send(GET, "/api/properties/" + UNKNOWN_ID + "/rooms", token, null), 404, "NOT_FOUND", "Property not found");
    }

    @Test
    void createRoomWithZeroCapacityReturns400() throws Exception {
        String propertyId = createProperty("Capacity PG");

        assertError(send(POST, "/api/properties/" + propertyId + "/rooms", token, roomJson("101", 0)), 400, "BAD_REQUEST",
                "capacity must be greater than 0");
    }

    @Test
    void duplicateRoomNumberInSamePropertyReturns409() throws Exception {
        String propertyId = createProperty("Duplicate Room PG");
        createRoom(propertyId, "101", 2);

        assertError(send(POST, "/api/properties/" + propertyId + "/rooms", token, roomJson("101", 3)), 409, "CONFLICT",
                "Room number already exists in this property");
    }

    @Test
    void sameRoomNumberInDifferentPropertiesIsAllowed() throws Exception {
        createRoom(createProperty("First PG"), "101", 2);

        HttpResponse<Buffer> response = send(POST, "/api/properties/" + createProperty("Second PG") + "/rooms", token, roomJson("101", 2));

        assertEquals(201, response.statusCode());
    }

    @Test
    void getAndUpdateRoom() throws Exception {
        String roomId = createRoom(createProperty("Room Edit PG"), "101", 2);

        assertEquals("101", send(GET, "/api/rooms/" + roomId, token, null).bodyAsJsonObject().getString("roomNumber"));

        HttpResponse<Buffer> updated = send(PUT, "/api/rooms/" + roomId, token, roomJson("102", 4));
        assertEquals(200, updated.statusCode());
        assertEquals("102", updated.bodyAsJsonObject().getString("roomNumber"));
        assertEquals(4, updated.bodyAsJsonObject().getInteger("capacity"));
    }

    @Test
    void reducingCapacityBelowBedCountReturns409() throws Exception {
        String roomId = createRoom(createProperty("Shrink PG"), "101", 3);
        createBed(roomId, "A");
        createBed(roomId, "B");

        assertError(send(PUT, "/api/rooms/" + roomId, token, roomJson("101", 1)), 409, "CONFLICT",
                "Capacity cannot be less than the number of beds in the room (2)");
    }

    @Test
    void deleteRoomWithBedsReturns409ButEmptyRoomIsDeleted() throws Exception {
        String propertyId = createProperty("Room Delete PG");
        String roomWithBed = createRoom(propertyId, "101", 2);
        createBed(roomWithBed, "A");
        String emptyRoom = createRoom(propertyId, "102", 2);

        assertError(send(DELETE, "/api/rooms/" + roomWithBed, token, null), 409, "CONFLICT", "Room cannot be deleted while it still has beds");
        assertEquals(204, send(DELETE, "/api/rooms/" + emptyRoom, token, null).statusCode());
        assertError(send(GET, "/api/rooms/" + emptyRoom, token, null), 404, "NOT_FOUND", "Room not found");
    }

    // ---------- beds ----------

    @Test
    void createBedForUnknownRoomReturns404() throws Exception {
        assertError(send(POST, "/api/rooms/" + UNKNOWN_ID + "/beds", token, bedJson("A")), 404, "NOT_FOUND", "Room not found");
    }

    @Test
    void duplicateBedNumberInSameRoomReturns409() throws Exception {
        String roomId = createRoom(createProperty("Duplicate Bed PG"), "101", 3);
        createBed(roomId, "A");

        assertError(send(POST, "/api/rooms/" + roomId + "/beds", token, bedJson("A")), 409, "CONFLICT", "Bed number already exists in this room");
    }

    @Test
    void addingBedToFullRoomReturns409() throws Exception {
        String roomId = createRoom(createProperty("Full Room PG"), "101", 1);
        createBed(roomId, "A");

        assertError(send(POST, "/api/rooms/" + roomId + "/beds", token, bedJson("B")), 409, "CONFLICT", "Room is full: it already has 1 of 1 beds");
    }

    @Test
    void updateBedNumber() throws Exception {
        String bedId = createBed(createRoom(createProperty("Bed Edit PG"), "101", 2), "A");

        HttpResponse<Buffer> response = send(PUT, "/api/beds/" + bedId, token, bedJson("Z"));

        assertEquals(200, response.statusCode());
        assertEquals("Z", response.bodyAsJsonObject().getString("bedNumber"));
    }

    @Test
    void invalidBedStatusReturns400() throws Exception {
        String bedId = createBed(createRoom(createProperty("Status PG"), "101", 2), "A");

        assertError(send(PATCH, "/api/beds/" + bedId + "/status", token, statusJson("BROKEN")), 400, "BAD_REQUEST",
                "status must be AVAILABLE or OCCUPIED");
    }

    @Test
    void getUnknownBedReturns404() throws Exception {
        assertError(send(GET, "/api/beds/" + UNKNOWN_ID, token, null), 404, "NOT_FOUND", "Bed not found");
    }

    @Test
    void deleteAvailableBedButNotOccupiedBed() throws Exception {
        String roomId = createRoom(createProperty("Bed Delete PG"), "101", 2);
        String freeBed = createBed(roomId, "A");
        String occupiedBed = createBed(roomId, "B");
        checkInNewTenant(occupiedBed);

        assertEquals(204, send(DELETE, "/api/beds/" + freeBed, token, null).statusCode());
        assertError(send(GET, "/api/beds/" + freeBed, token, null), 404, "NOT_FOUND", "Bed not found");
        assertError(send(DELETE, "/api/beds/" + occupiedBed, token, null), 409, "CONFLICT", "Cannot delete an occupied bed");
    }

    // ---------- helpers ----------

    private static String createProperty(String name) throws Exception {
        return createAndGetId(POST, "/api/properties", propertyJson(name));
    }

    private static String createRoom(String propertyId, String roomNumber, int capacity) throws Exception {
        return createAndGetId(POST, "/api/properties/" + propertyId + "/rooms", roomJson(roomNumber, capacity));
    }

    private static String createBed(String roomId, String bedNumber) throws Exception {
        return createAndGetId(POST, "/api/rooms/" + roomId + "/beds", bedJson(bedNumber));
    }

    /** The only way to make a bed OCCUPIED: create a tenant and check them in. */
    private static void checkInNewTenant(String bedId) throws Exception {
        JsonObject tenant = new JsonObject().put("name", "Bed Tenant").put("phone", "9876543210")
                .put("joiningDate", "2026-10-01").put("monthlyRent", 8000).put("securityDeposit", 5000);
        String tenantId = createAndGetId(POST, "/api/tenants", tenant);
        HttpResponse<Buffer> response = send(POST, "/api/tenants/" + tenantId + "/check-in", token, new JsonObject().put("bedId", bedId));
        assertEquals(200, response.statusCode(), () -> "Check-in failed: " + response.bodyAsString());
    }

    private static String createAndGetId(HttpMethod method, String path, JsonObject body) throws Exception {
        HttpResponse<Buffer> response = send(method, path, token, body);
        assertEquals(201, response.statusCode(), () -> "Setup request failed: " + response.bodyAsString());
        return response.bodyAsJsonObject().getString("id");
    }

    private static JsonObject propertyJson(String name) {
        return new JsonObject().put("name", name).put("address", "123 Main Road").put("city", "Hyderabad");
    }

    private static JsonObject roomJson(String roomNumber, int capacity) {
        return new JsonObject().put("roomNumber", roomNumber).put("capacity", capacity);
    }

    private static JsonObject bedJson(String bedNumber) {
        return new JsonObject().put("bedNumber", bedNumber);
    }

    private static JsonObject statusJson(String status) {
        return new JsonObject().put("status", status);
    }
}
