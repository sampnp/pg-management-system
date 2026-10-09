package com.pgmanager;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.pgmanager.TestFutures.await;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.POST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Many requests at the same moment against the same row. Each test sends all requests before waiting for any
 * answer, so they really overlap in the server, then checks that the database rules still hold.
 */
class ConcurrencyIntegrationTest extends ApiTestBase {

    private static final int PARALLEL = 10;
    private static String token;

    @BeforeAll
    static void login() throws Exception {
        token = registerAndLogin("MANAGER");
    }

    @Test
    void parallelBedCreationNeverExceedsTheRoomCapacity() throws Exception {
        for (int round = 0; round < 3; round++) {
            String roomId = createRoom(2);
            List<Future<HttpResponse<Buffer>>> requests = new ArrayList<>();
            for (int i = 0; i < PARALLEL; i++) {
                requests.add(post("/api/rooms/" + roomId + "/beds", new JsonObject().put("bedNumber", "B" + i)));
            }

            List<HttpResponse<Buffer>> responses = awaitAll(requests);

            assertEquals(2, count(responses, 201), "exactly the capacity may be created");
            assertEquals(PARALLEL - 2, count(responses, 409));
            assertEquals(2, getBedCount(roomId));
        }
    }

    @Test
    void capacityCannotDropBelowBedsCreatedAtTheSameTime() throws Exception {
        for (int round = 0; round < 5; round++) {
            String propertyId = createProperty();
            String roomId = createAndGetId("/api/properties/" + propertyId + "/rooms", new JsonObject().put("roomNumber", "1").put("capacity", 3));
            createAndGetId("/api/rooms/" + roomId + "/beds", new JsonObject().put("bedNumber", "A"));

            // At the same moment: lower the capacity to 1, and add two more beds
            List<Future<HttpResponse<Buffer>>> requests = List.of(
                    client.put("/api/rooms/" + roomId).putHeader("Authorization", "Bearer " + token)
                            .sendJsonObject(new JsonObject().put("roomNumber", "1").put("capacity", 1)),
                    post("/api/rooms/" + roomId + "/beds", new JsonObject().put("bedNumber", "B")),
                    post("/api/rooms/" + roomId + "/beds", new JsonObject().put("bedNumber", "C")));
            awaitAll(requests);

            int capacity = send(GET, "/api/rooms/" + roomId, token, null).bodyAsJsonObject().getInteger("capacity");
            int beds = getBedCount(roomId);
            assertTrue(beds <= capacity, "round " + round + ": " + beds + " beds in a room with capacity " + capacity);
        }
    }

    @Test
    void parallelCheckInsToTheSameBedLetExactlyOneTenantIn() throws Exception {
        String bedId = createAndGetId("/api/rooms/" + createRoom(1) + "/beds", new JsonObject().put("bedNumber", "A"));
        List<Future<HttpResponse<Buffer>>> requests = new ArrayList<>();
        for (int i = 0; i < PARALLEL; i++) {
            requests.add(post("/api/tenants/" + createTenant() + "/check-in", new JsonObject().put("bedId", bedId)));
        }

        List<HttpResponse<Buffer>> responses = awaitAll(requests);

        assertEquals(1, count(responses, 200));
        assertEquals(PARALLEL - 1, count(responses, 409));
        assertEquals("OCCUPIED", send(GET, "/api/beds/" + bedId, token, null).bodyAsJsonObject().getString("status"));
    }

    @Test
    void parallelCheckInsOfOneTenantToDifferentBedsSucceedOnce() throws Exception {
        String tenantId = createTenant();
        String roomId = createRoom(PARALLEL);
        List<Future<HttpResponse<Buffer>>> requests = new ArrayList<>();
        for (int i = 0; i < PARALLEL; i++) {
            String bedId = createAndGetId("/api/rooms/" + roomId + "/beds", new JsonObject().put("bedNumber", "B" + i));
            requests.add(post("/api/tenants/" + tenantId + "/check-in", new JsonObject().put("bedId", bedId)));
        }

        List<HttpResponse<Buffer>> responses = awaitAll(requests);

        assertEquals(1, count(responses, 200));
        assertEquals(PARALLEL - 1, count(responses, 409));
        assertEquals(1, send(GET, "/api/tenants/" + tenantId + "/history", token, null).bodyAsJsonArray().size());
    }

    @Test
    void parallelStatusChangesOfOneIssueApplyOnce() throws Exception {
        String tenantId = createTenant();
        String bedId = createAndGetId("/api/rooms/" + createRoom(1) + "/beds", new JsonObject().put("bedNumber", "A"));
        assertEquals(200, send(POST, "/api/tenants/" + tenantId + "/check-in", token, new JsonObject().put("bedId", bedId)).statusCode());
        String issueId = createAndGetId("/api/maintenance", new JsonObject().put("tenantId", tenantId).put("title", "Leak")
                .put("description", "Leak").put("category", "PLUMBING"));

        List<Future<HttpResponse<Buffer>>> requests = new ArrayList<>();
        for (int i = 0; i < PARALLEL; i++) {
            requests.add(client.patch("/api/maintenance/" + issueId + "/status").putHeader("Authorization", "Bearer " + token)
                    .sendJsonObject(new JsonObject().put("status", "IN_PROGRESS")));
        }

        List<HttpResponse<Buffer>> responses = awaitAll(requests);

        assertEquals(1, count(responses, 200), "OPEN -> IN_PROGRESS happens once");
        assertEquals(PARALLEL - 1, count(responses, 409));
    }

    // ---------- helpers ----------

    private static Future<HttpResponse<Buffer>> post(String path, JsonObject body) {
        return client.post(path).putHeader("Authorization", "Bearer " + token).sendJsonObject(body);
    }

    private static List<HttpResponse<Buffer>> awaitAll(List<Future<HttpResponse<Buffer>>> requests) throws Exception {
        await(Future.join(requests));
        return requests.stream().map(Future::result).toList();
    }

    private static long count(List<HttpResponse<Buffer>> responses, int status) {
        return responses.stream().filter(r -> r.statusCode() == status).count();
    }

    private static int getBedCount(String roomId) throws Exception {
        return send(GET, "/api/rooms/" + roomId + "/beds", token, null).bodyAsJsonArray().size();
    }

    private static String createProperty() throws Exception {
        return createAndGetId("/api/properties", new JsonObject().put("name", "Race PG").put("address", "1 Road").put("city", "Pune"));
    }

    private static String createRoom(int capacity) throws Exception {
        return createAndGetId("/api/properties/" + createProperty() + "/rooms", new JsonObject().put("roomNumber", "1").put("capacity", capacity));
    }

    private static String createTenant() throws Exception {
        return createAndGetId("/api/tenants", new JsonObject().put("name", "Racer").put("phone", "9876543210")
                .put("joiningDate", "2026-01-01").put("monthlyRent", 5000).put("securityDeposit", 0));
    }

    private static String createAndGetId(String path, JsonObject body) throws Exception {
        HttpResponse<Buffer> response = send(POST, path, token, body);
        assertEquals(201, response.statusCode(), () -> "Setup request failed: " + response.bodyAsString());
        return response.bodyAsJsonObject().getString("id");
    }
}
