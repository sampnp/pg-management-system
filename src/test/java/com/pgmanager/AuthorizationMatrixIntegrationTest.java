package com.pgmanager;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static io.vertx.core.http.HttpMethod.DELETE;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.PATCH;
import static io.vertx.core.http.HttpMethod.POST;
import static io.vertx.core.http.HttpMethod.PUT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every protected endpoint against every role that must not use it. Real ids are used, so a 403 really comes
 * from the access rules and not from a missing row.
 */
class AuthorizationMatrixIntegrationTest extends ApiTestBase {

    private record Call(HttpMethod method, String path) {
    }

    private static String managerToken;
    private static String tenantToken;
    private static String tenantId;
    private static String otherTenantId;
    private static String otherIssueId;
    private static String propertyId;
    private static String roomId;
    private static String bedId;
    private static String paymentId;
    private static String tenantUserId;

    @BeforeAll
    static void setUp() throws Exception {
        managerToken = registerAndLogin("MANAGER");
        propertyId = create("/api/properties", new JsonObject().put("name", "Matrix PG").put("address", "1 Road").put("city", "Pune"));
        roomId = create("/api/properties/" + propertyId + "/rooms", new JsonObject().put("roomNumber", "1").put("capacity", 3));
        bedId = create("/api/rooms/" + roomId + "/beds", new JsonObject().put("bedNumber", "A"));
        String otherBedId = create("/api/rooms/" + roomId + "/beds", new JsonObject().put("bedNumber", "B"));

        tenantId = createTenant("Matrix Tenant");
        otherTenantId = createTenant("Other Tenant");
        assertEquals(200, send(POST, "/api/tenants/" + tenantId + "/check-in", managerToken, new JsonObject().put("bedId", bedId)).statusCode());
        assertEquals(200, send(POST, "/api/tenants/" + otherTenantId + "/check-in", managerToken, new JsonObject().put("bedId", otherBedId)).statusCode());
        otherIssueId = create("/api/maintenance", new JsonObject().put("tenantId", otherTenantId).put("title", "Other issue")
                .put("description", "Belongs to the other tenant").put("category", "OTHER"));
        paymentId = create("/api/payments", new JsonObject().put("tenantId", tenantId).put("amount", 5000)
                .put("rentMonth", "2026-10").put("status", "PENDING"));

        String email = uniqueEmail();
        tenantUserId = send(POST, "/api/tenants/" + tenantId + "/account", managerToken,
                new JsonObject().put("email", email).put("password", "password123")).bodyAsJsonObject().getString("id");
        tenantToken = login(email, "password123").bodyAsJsonObject().getString("token");
    }

    /** Everything only ADMIN or MANAGER may use - including the tenant's own tenant record and payments. */
    private static List<Call> staffOnly() {
        return List.of(
                new Call(GET, "/api/dashboard"),
                new Call(GET, "/api/properties/" + propertyId + "/dashboard"),
                new Call(POST, "/api/properties"), new Call(GET, "/api/properties"),
                new Call(GET, "/api/properties/" + propertyId), new Call(PUT, "/api/properties/" + propertyId),
                new Call(DELETE, "/api/properties/" + propertyId),
                new Call(POST, "/api/properties/" + propertyId + "/rooms"), new Call(GET, "/api/properties/" + propertyId + "/rooms"),
                new Call(GET, "/api/rooms/" + roomId), new Call(PUT, "/api/rooms/" + roomId), new Call(DELETE, "/api/rooms/" + roomId),
                new Call(POST, "/api/rooms/" + roomId + "/beds"), new Call(GET, "/api/rooms/" + roomId + "/beds"),
                new Call(GET, "/api/beds/" + bedId), new Call(PUT, "/api/beds/" + bedId), new Call(DELETE, "/api/beds/" + bedId),
                new Call(PATCH, "/api/beds/" + bedId + "/status"),
                new Call(POST, "/api/tenants"), new Call(GET, "/api/tenants"),
                new Call(GET, "/api/tenants/" + tenantId), new Call(PUT, "/api/tenants/" + tenantId),
                new Call(DELETE, "/api/tenants/" + otherTenantId),
                new Call(POST, "/api/tenants/" + tenantId + "/check-in"), new Call(POST, "/api/tenants/" + tenantId + "/check-out"),
                new Call(GET, "/api/tenants/" + tenantId + "/bed"), new Call(GET, "/api/tenants/" + tenantId + "/history"),
                new Call(POST, "/api/tenants/" + tenantId + "/account"),
                new Call(POST, "/api/payments"), new Call(GET, "/api/payments"),
                new Call(GET, "/api/payments/" + paymentId), new Call(PUT, "/api/payments/" + paymentId),
                new Call(GET, "/api/tenants/" + tenantId + "/payments"), new Call(GET, "/api/tenants/" + otherTenantId + "/payments"),
                new Call(GET, "/api/maintenance"),
                new Call(PATCH, "/api/maintenance/" + otherIssueId + "/assign"), new Call(PATCH, "/api/maintenance/" + otherIssueId + "/status"));
    }

    private static List<Call> adminOnly() {
        return List.of(
                new Call(GET, "/api/admin/test"),
                new Call(POST, "/api/admin/users"),
                new Call(PATCH, "/api/admin/users/" + tenantUserId + "/role"),
                new Call(PATCH, "/api/admin/users/" + tenantUserId + "/active"));
    }

    @Test
    void tenantGets403OnEveryStaffAndAdminEndpoint() throws Exception {
        for (Call call : concat(staffOnly(), adminOnly())) {
            HttpResponse<Buffer> response = send(call.method(), call.path(), tenantToken, new JsonObject());
            assertError(response, 403, "FORBIDDEN", "Insufficient permissions");
        }
        // Nothing was changed: the tenant is still checked in and the property still exists
        assertEquals("ACTIVE", send(GET, "/api/tenants/" + tenantId, managerToken, null).bodyAsJsonObject().getString("status"));
        assertEquals(200, send(GET, "/api/properties/" + propertyId, managerToken, null).statusCode());
    }

    @Test
    void tenantCannotReachAnotherTenantsMaintenance() throws Exception {
        String notYours = "You can only access your own maintenance issues";
        assertError(send(GET, "/api/maintenance/" + otherIssueId, tenantToken, null), 403, "FORBIDDEN", notYours);
        assertError(send(PUT, "/api/maintenance/" + otherIssueId, tenantToken, new JsonObject().put("title", "x")
                .put("description", "x").put("category", "OTHER")), 403, "FORBIDDEN", notYours);
        assertError(send(GET, "/api/tenants/" + otherTenantId + "/maintenance", tenantToken, null), 403, "FORBIDDEN", notYours);
        assertError(send(POST, "/api/maintenance", tenantToken, new JsonObject().put("tenantId", otherTenantId)
                        .put("title", "x").put("description", "x").put("category", "OTHER")),
                403, "FORBIDDEN", "Tenants can only report issues for themselves");

        // ...while their own maintenance history works
        assertEquals(200, send(GET, "/api/tenants/" + tenantId + "/maintenance", tenantToken, null).statusCode());
    }

    @Test
    void managerGets403OnEveryAdminEndpoint() throws Exception {
        for (Call call : adminOnly()) {
            assertError(send(call.method(), call.path(), managerToken, new JsonObject()), 403, "FORBIDDEN", "Insufficient permissions");
        }
    }

    @Test
    void everyProtectedEndpointNeedsAToken() throws Exception {
        List<Call> all = concat(concat(staffOnly(), adminOnly()), List.of(
                new Call(GET, "/api/auth/me"), new Call(PATCH, "/api/auth/password"),
                new Call(POST, "/api/maintenance"), new Call(GET, "/api/maintenance/" + otherIssueId),
                new Call(PUT, "/api/maintenance/" + otherIssueId), new Call(GET, "/api/tenants/" + tenantId + "/maintenance")));
        for (Call call : all) {
            assertError(send(call.method(), call.path(), null, new JsonObject()), 401, "UNAUTHORIZED", "Missing or invalid Authorization header");
            assertError(send(call.method(), call.path(), "not.a.jwt", new JsonObject()), 401, "UNAUTHORIZED", "Invalid or expired token");
        }
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        return Stream.concat(a.stream(), b.stream()).toList();
    }

    private static String createTenant(String name) throws Exception {
        return create("/api/tenants", new JsonObject().put("name", name).put("phone", "9876543210")
                .put("joiningDate", "2026-01-01").put("monthlyRent", 5000).put("securityDeposit", 0));
    }

    private static String create(String path, JsonObject body) throws Exception {
        HttpResponse<Buffer> response = send(POST, path, managerToken, body);
        assertEquals(201, response.statusCode(), () -> "Setup request failed: " + response.bodyAsString());
        return response.bodyAsJsonObject().getString("id");
    }
}
