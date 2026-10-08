package com.pgmanager;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.POST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ?page and ?size on the list endpoints. The database is shared with other test classes, so counts are checked
 * with filters that only match this test's data (a fresh tenant), or relative to what was just created.
 */
class PaginationApiIntegrationTest extends ApiTestBase {

    private static String token;

    @BeforeAll
    static void login() throws Exception {
        token = registerAndLogin("MANAGER");
    }

    @Test
    void defaultPageIsTheNewestTwenty() throws Exception {
        String newest = createAndGetId("/api/properties", new JsonObject().put("name", "Newest PG").put("address", "1 Road").put("city", "Pune"));

        JsonObject page = getPage("/api/properties");

        assertEquals(0, page.getInteger("page"));
        assertEquals(20, page.getInteger("size"));
        assertTrue(page.getJsonArray("items").size() <= 20);
        assertEquals(newest, page.getJsonArray("items").getJsonObject(0).getString("id"));
        long total = page.getLong("totalItems");
        assertEquals((total + 19) / 20, (long) page.getInteger("totalPages"));
    }

    @Test
    void pagesWalkThroughFilteredPaymentsInAStableOrder() throws Exception {
        String tenantId = createTenant("Paging Tenant");
        // Five payments in different months: PAID Jan, Feb, Mar and PENDING Apr, May
        List<String> created = new ArrayList<>();
        for (String month : List.of("2026-01", "2026-02", "2026-03")) {
            created.add(createAndGetId("/api/payments", new JsonObject().put("tenantId", tenantId).put("amount", 1000)
                    .put("rentMonth", month).put("status", "PAID").put("paymentMethod", "CASH").put("paymentDate", month + "-05")));
        }
        for (String month : List.of("2026-04", "2026-05")) {
            created.add(createAndGetId("/api/payments", new JsonObject().put("tenantId", tenantId).put("amount", 1000)
                    .put("rentMonth", month).put("status", "PENDING")));
        }
        String base = "/api/payments?tenantId=" + tenantId;

        JsonObject first = getPage(base + "&size=2");
        JsonObject second = getPage(base + "&size=2&page=1");
        JsonObject last = getPage(base + "&size=2&page=2");
        JsonObject pastTheEnd = getPage(base + "&size=2&page=3");

        assertEquals(5, first.getLong("totalItems"));
        assertEquals(3, first.getInteger("totalPages"));
        assertEquals(2, first.getJsonArray("items").size());
        assertEquals(2, second.getJsonArray("items").size());
        assertEquals(1, last.getJsonArray("items").size());
        // An empty page past the end still reports the totals
        assertEquals(0, pastTheEnd.getJsonArray("items").size());
        assertEquals(5, pastTheEnd.getLong("totalItems"));
        assertEquals(3, pastTheEnd.getInteger("page"));

        // Walking the pages gives every payment exactly once, newest rent month first - the same order as one big page
        List<String> walked = new ArrayList<>();
        walked.addAll(ids(first.getJsonArray("items")));
        walked.addAll(ids(second.getJsonArray("items")));
        walked.addAll(ids(last.getJsonArray("items")));
        assertEquals(List.of(created.get(4), created.get(3), created.get(2), created.get(1), created.get(0)), walked);
        assertEquals(walked, ids(getPage(base + "&size=100").getJsonArray("items")));

        // Filters and paging together
        JsonObject paid = getPage(base + "&status=PAID&size=2&page=1");
        assertEquals(3, paid.getLong("totalItems"));
        assertEquals(2, paid.getInteger("totalPages"));
        assertEquals(List.of(created.get(0)), ids(paid.getJsonArray("items")));
    }

    @Test
    void maintenanceAndTenantListsArePagedToo() throws Exception {
        String tenantId = createTenant("Paging Maintenance Tenant");
        String bedId = createBed();
        HttpResponse<Buffer> checkIn = send(POST, "/api/tenants/" + tenantId + "/check-in", token, new JsonObject().put("bedId", bedId));
        assertEquals(200, checkIn.statusCode(), checkIn::bodyAsString);
        for (int i = 1; i <= 3; i++) {
            createAndGetId("/api/maintenance", new JsonObject().put("tenantId", tenantId).put("title", "Issue " + i)
                    .put("description", "Paging test issue").put("category", "OTHER"));
        }

        JsonObject issues = getPage("/api/maintenance?tenantId=" + tenantId + "&size=2&page=1");
        assertEquals(3, issues.getLong("totalItems"));
        assertEquals(2, issues.getInteger("totalPages"));
        assertEquals(List.of("Issue 1"), issues.getJsonArray("items").stream().map(i -> ((JsonObject) i).getString("title")).toList());

        JsonObject tenants = getPage("/api/tenants?size=1");
        assertEquals(1, tenants.getJsonArray("items").size());
        assertEquals(tenantId, tenants.getJsonArray("items").getJsonObject(0).getString("id"));
        assertEquals(tenants.getLong("totalItems"), (long) tenants.getInteger("totalPages"));
    }

    @Test
    void maximumSizeIsAllowedAndInvalidValuesAreRejected() throws Exception {
        assertEquals(100, getPage("/api/tenants?size=100").getInteger("size"));

        for (String path : List.of("/api/properties", "/api/tenants", "/api/payments", "/api/maintenance")) {
            assertError(send(GET, path + "?size=101", token, null), 400, "BAD_REQUEST", "size must be a number between 1 and 100");
            assertError(send(GET, path + "?size=0", token, null), 400, "BAD_REQUEST", "size must be a number between 1 and 100");
            assertError(send(GET, path + "?page=-1", token, null), 400, "BAD_REQUEST", "page must be a number of 0 or more");
            assertError(send(GET, path + "?page=two", token, null), 400, "BAD_REQUEST", "page must be a number of 0 or more");
        }
    }

    // ---------- helpers ----------

    private static JsonObject getPage(String path) throws Exception {
        HttpResponse<Buffer> response = send(GET, path, token, null);
        assertEquals(200, response.statusCode(), () -> "GET " + path + " failed: " + response.bodyAsString());
        return response.bodyAsJsonObject();
    }

    private static List<String> ids(JsonArray items) {
        return items.stream().map(item -> ((JsonObject) item).getString("id")).toList();
    }

    private static String createTenant(String name) throws Exception {
        return createAndGetId("/api/tenants", new JsonObject().put("name", name).put("phone", "9876543210")
                .put("joiningDate", "2026-01-01").put("monthlyRent", 1000).put("securityDeposit", 0));
    }

    private static String createBed() throws Exception {
        String propertyId = createAndGetId("/api/properties", new JsonObject().put("name", "Paging PG").put("address", "2 Road").put("city", "Pune"));
        String roomId = createAndGetId("/api/properties/" + propertyId + "/rooms", new JsonObject().put("roomNumber", "1").put("capacity", 1));
        return createAndGetId("/api/rooms/" + roomId + "/beds", new JsonObject().put("bedNumber", "A"));
    }

    private static String createAndGetId(String path, JsonObject body) throws Exception {
        HttpResponse<Buffer> response = send(POST, path, token, body);
        assertEquals(201, response.statusCode(), () -> "Setup request failed: " + response.bodyAsString());
        return response.bodyAsJsonObject().getString("id");
    }
}
