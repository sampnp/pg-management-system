package com.pgmanager;

import com.pgmanager.config.Database;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.pgmanager.TestFutures.await;
import static com.pgmanager.TestFutures.awaitFailure;
import static io.vertx.core.http.HttpMethod.DELETE;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.PATCH;
import static io.vertx.core.http.HttpMethod.POST;
import static io.vertx.core.http.HttpMethod.PUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end tests for maintenance issues and tenant logins, against a real PostgreSQL. */
class MaintenanceApiIntegrationTest extends ApiTestBase {

    private static final String UNKNOWN_ID = UUID.randomUUID().toString();

    private static String managerToken;
    private static String adminToken;

    /** A checked-in tenant who has a login. */
    private record TenantLogin(String tenantId, String bedId, String roomId, String propertyId, String token) {
    }

    @BeforeAll
    static void loginAsStaff() throws Exception {
        managerToken = registerAndLogin("MANAGER");
        adminToken = registerAndLogin("ADMIN");
    }

    @Test
    void fullMaintenanceFlow() throws Exception {
        TenantLogin tenant = checkedInTenantWithLogin("Ravi");

        // Tenant reports an issue: no tenantId needed, it comes from their token
        HttpResponse<Buffer> created = send(POST, "/api/maintenance", tenant.token(), new JsonObject()
                .put("title", "Bathroom tap leaking").put("description", "The bathroom tap has been leaking since morning.")
                .put("category", "PLUMBING").put("priority", "HIGH"));
        assertEquals(201, created.statusCode(), created::bodyAsString);
        JsonObject issue = created.bodyAsJsonObject();
        String issueId = issue.getString("id");
        assertEquals(tenant.tenantId(), issue.getString("tenantId"));
        assertEquals(tenant.bedId(), issue.getString("bedId"));
        assertEquals(tenant.roomId(), issue.getString("roomId"));
        assertEquals(tenant.propertyId(), issue.getString("propertyId"));
        assertEquals("OPEN", issue.getString("status"));
        assertEquals("HIGH", issue.getString("priority"));
        assertNull(issue.getString("assignedTo"));
        assertNull(issue.getString("resolvedAt"));

        // Manager sees it and assigns it to themselves
        assertEquals(issueId, getJson("/api/maintenance/" + issueId, managerToken).getString("id"));
        assertTrue(ids(getItems("/api/maintenance?status=OPEN", managerToken)).contains(issueId));
        String managerId = getJson("/api/auth/me", managerToken).getString("id");
        HttpResponse<Buffer> assigned = send(PATCH, "/api/maintenance/" + issueId + "/assign", managerToken,
                new JsonObject().put("assignedTo", managerId));
        assertEquals(200, assigned.statusCode(), assigned::bodyAsString);
        assertEquals(managerId, assigned.bodyAsJsonObject().getString("assignedTo"));

        // OPEN -> IN_PROGRESS -> RESOLVED
        assertEquals("IN_PROGRESS", changeStatus(issueId, "IN_PROGRESS", managerToken).getString("status"));
        assertNull(getJson("/api/maintenance/" + issueId, managerToken).getString("resolvedAt"));
        JsonObject resolved = changeStatus(issueId, "RESOLVED", managerToken);
        assertEquals("RESOLVED", resolved.getString("status"));
        Instant resolvedAt = Instant.parse(resolved.getString("resolvedAt"));
        assertFalse(resolvedAt.isBefore(Instant.parse(resolved.getString("createdAt"))));

        // Tenant sees the resolved issue in their own history
        JsonArray history = getArray("/api/tenants/" + tenant.tenantId() + "/maintenance", tenant.token());
        assertEquals(List.of(issueId), ids(history));
        assertEquals("RESOLVED", history.getJsonObject(0).getString("status"));

        // After check-out the history is still there, and the issue still points at the old bed
        assertEquals(200, send(POST, "/api/tenants/" + tenant.tenantId() + "/check-out", managerToken, null).statusCode());
        JsonArray afterCheckout = getArray("/api/tenants/" + tenant.tenantId() + "/maintenance", managerToken);
        assertEquals(List.of(issueId), ids(afterCheckout));
        assertEquals(tenant.bedId(), afterCheckout.getJsonObject(0).getString("bedId"));
        assertEquals(resolvedAt, Instant.parse(afterCheckout.getJsonObject(0).getString("resolvedAt")));
        assertEquals(List.of(issueId), ids(getArray("/api/tenants/" + tenant.tenantId() + "/maintenance", tenant.token())));
    }

    @Test
    void reopenClearsResolvedAtAndClosedIsFinal() throws Exception {
        TenantLogin tenant = checkedInTenantWithLogin("Reopen Tenant");
        String issueId = createIssue(tenant.token(), null, "Fan not working", "ELECTRICAL", null);

        assertNotNull(changeStatus(issueId, "RESOLVED", managerToken).getString("resolvedAt"));   // OPEN -> RESOLVED directly
        JsonObject reopened = changeStatus(issueId, "OPEN", managerToken);
        assertEquals("OPEN", reopened.getString("status"));
        assertNull(reopened.getString("resolvedAt"));

        changeStatus(issueId, "RESOLVED", managerToken);
        JsonObject closed = changeStatus(issueId, "CLOSED", adminToken);
        assertEquals("CLOSED", closed.getString("status"));
        assertNotNull(closed.getString("resolvedAt"));

        assertError(send(PATCH, "/api/maintenance/" + issueId + "/status", managerToken, new JsonObject().put("status", "OPEN")),
                409, "CONFLICT", "Cannot change status from CLOSED to OPEN");
        assertError(send(PUT, "/api/maintenance/" + issueId, managerToken, issueJson(null, "Fan", "Fan", "ELECTRICAL", null)),
                409, "CONFLICT", "A CLOSED issue cannot be changed");
        assertError(send(PATCH, "/api/maintenance/" + issueId + "/assign", managerToken, new JsonObject().put("assignedTo", UNKNOWN_ID)),
                409, "CONFLICT", "A CLOSED issue cannot be changed");
    }

    @Test
    void tenantAccountsAreCreatedByStaffAndOnlyGetTenantAccess() throws Exception {
        String tenantId = createTenant("Account Tenant");
        String email = uniqueEmail();

        HttpResponse<Buffer> created = send(POST, "/api/tenants/" + tenantId + "/account", managerToken,
                new JsonObject().put("email", email).put("password", "password123").put("role", "ADMIN"));
        assertEquals(201, created.statusCode(), created::bodyAsString);
        assertEquals("TENANT", created.bodyAsJsonObject().getString("role"));
        assertEquals(tenantId, created.bodyAsJsonObject().getString("tenantId"));
        assertEquals("Account Tenant", created.bodyAsJsonObject().getString("name"));

        // The token says who the tenant is
        String tenantToken = login(email, "password123").bodyAsJsonObject().getString("token");
        JsonObject me = getJson("/api/auth/me", tenantToken);
        assertEquals("TENANT", me.getString("role"));
        assertEquals(tenantId, me.getString("tenantId"));

        // One login per tenant, and only for tenants that exist
        assertError(send(POST, "/api/tenants/" + tenantId + "/account", managerToken,
                new JsonObject().put("email", uniqueEmail()).put("password", "password123")), 409, "CONFLICT", "Tenant already has a login account");
        assertError(send(POST, "/api/tenants/" + UNKNOWN_ID + "/account", managerToken,
                new JsonObject().put("email", uniqueEmail()).put("password", "password123")), 404, "NOT_FOUND", "Tenant not found");

        // A tenant is not staff: every management endpoint is forbidden, including their own tenant record
        for (String path : List.of("/api/tenants", "/api/tenants/" + tenantId, "/api/tenants/" + tenantId + "/payments",
                "/api/properties", "/api/payments", "/api/maintenance", "/api/admin/test")) {
            assertError(send(GET, path, tenantToken, null), 403, "FORBIDDEN", "Insufficient permissions");
        }
        assertError(send(POST, "/api/tenants/" + tenantId + "/account", tenantToken,
                new JsonObject().put("email", uniqueEmail()).put("password", "password123")), 403, "FORBIDDEN", "Insufficient permissions");

        // An admin can't turn a tenant account into staff
        assertError(send(PATCH, "/api/admin/users/" + me.getString("id") + "/role", adminToken, new JsonObject().put("role", "ADMIN")),
                409, "CONFLICT", "The role of a tenant account cannot be changed");
    }

    @Test
    void unauthenticatedRequestsGet401() throws Exception {
        String tenantId = createTenant("No Token Tenant");
        for (var request : List.of(
                List.of("POST", "/api/maintenance"), List.of("GET", "/api/maintenance"), List.of("GET", "/api/maintenance/" + UNKNOWN_ID),
                List.of("PUT", "/api/maintenance/" + UNKNOWN_ID), List.of("PATCH", "/api/maintenance/" + UNKNOWN_ID + "/assign"),
                List.of("PATCH", "/api/maintenance/" + UNKNOWN_ID + "/status"), List.of("GET", "/api/tenants/" + tenantId + "/maintenance"))) {
            HttpResponse<Buffer> response = send(HttpMethod.valueOf(request.get(0)), request.get(1), null, new JsonObject());
            assertError(response, 401, "UNAUTHORIZED", "Missing or invalid Authorization header");
        }
    }

    @Test
    void tenantCannotTouchAnotherTenantsIssues() throws Exception {
        TenantLogin ravi = checkedInTenantWithLogin("Ravi Two");
        TenantLogin priya = checkedInTenantWithLogin("Priya");
        String priyaIssue = createIssue(priya.token(), null, "Window broken", "FURNITURE", "MEDIUM");

        assertError(send(GET, "/api/maintenance/" + priyaIssue, ravi.token(), null),
                403, "FORBIDDEN", "You can only access your own maintenance issues");
        assertError(send(PUT, "/api/maintenance/" + priyaIssue, ravi.token(), issueJson(null, "Mine now", "Mine now", "OTHER", null)),
                403, "FORBIDDEN", "You can only access your own maintenance issues");
        assertError(send(GET, "/api/tenants/" + priya.tenantId() + "/maintenance", ravi.token(), null),
                403, "FORBIDDEN", "You can only access your own maintenance issues");
        assertError(send(POST, "/api/maintenance", ravi.token(), issueJson(priya.tenantId(), "Fake", "Fake report", "OTHER", null)),
                403, "FORBIDDEN", "Tenants can only report issues for themselves");

        // Priya's issue is untouched and Ravi has no issues
        assertEquals("Window broken", getJson("/api/maintenance/" + priyaIssue, managerToken).getString("title"));
        assertEquals(0, getArray("/api/tenants/" + ravi.tenantId() + "/maintenance", ravi.token()).size());
    }

    @Test
    void tenantCannotChangeStaffControlledFields() throws Exception {
        TenantLogin tenant = checkedInTenantWithLogin("Limited Tenant");
        String issueId = createIssue(tenant.token(), null, "Dirty corridor", "CLEANING", "LOW");
        String managerId = getJson("/api/auth/me", managerToken).getString("id");

        assertError(send(PATCH, "/api/maintenance/" + issueId + "/assign", tenant.token(), new JsonObject().put("assignedTo", managerId)),
                403, "FORBIDDEN", "Insufficient permissions");
        assertError(send(PATCH, "/api/maintenance/" + issueId + "/status", tenant.token(), new JsonObject().put("status", "RESOLVED")),
                403, "FORBIDDEN", "Insufficient permissions");

        // Status and assignee sent in an edit are ignored: only title, description, category and priority change
        HttpResponse<Buffer> edited = send(PUT, "/api/maintenance/" + issueId, tenant.token(),
                issueJson(null, "Very dirty corridor", "Not cleaned for 3 days", "CLEANING", "HIGH")
                        .put("status", "CLOSED").put("assignedTo", managerId).put("resolvedAt", "2026-01-01T00:00:00Z"));
        assertEquals(200, edited.statusCode(), edited::bodyAsString);
        JsonObject issue = edited.bodyAsJsonObject();
        assertEquals("Very dirty corridor", issue.getString("title"));
        assertEquals("HIGH", issue.getString("priority"));
        assertEquals("OPEN", issue.getString("status"));
        assertNull(issue.getString("assignedTo"));
        assertNull(issue.getString("resolvedAt"));

        // Once staff start working on it, the tenant can no longer edit it
        changeStatus(issueId, "IN_PROGRESS", managerToken);
        assertError(send(PUT, "/api/maintenance/" + issueId, tenant.token(), issueJson(null, "Again", "Again", "CLEANING", null)),
                409, "CONFLICT", "An issue can only be edited by the tenant while it is OPEN");
    }

    @Test
    void adminAndManagerCanBothManageIssues() throws Exception {
        TenantLogin tenant = checkedInTenantWithLogin("Staff Managed Tenant");
        String adminId = getJson("/api/auth/me", adminToken).getString("id");

        // Admin reports on behalf of the tenant, manager assigns it to the admin, admin resolves it
        String issueId = createIssue(adminToken, tenant.tenantId(), "Geyser not heating", "APPLIANCE", "URGENT");
        HttpResponse<Buffer> assigned = send(PATCH, "/api/maintenance/" + issueId + "/assign", managerToken,
                new JsonObject().put("assignedTo", adminId));
        assertEquals(200, assigned.statusCode(), assigned::bodyAsString);
        assertEquals("RESOLVED", changeStatus(issueId, "RESOLVED", adminToken).getString("status"));

        HttpResponse<Buffer> edited = send(PUT, "/api/maintenance/" + issueId, managerToken,
                issueJson(tenant.tenantId(), "Geyser not heating", "Element replaced", "APPLIANCE", "HIGH"));
        assertEquals(200, edited.statusCode(), edited::bodyAsString);
        assertEquals("Element replaced", edited.bodyAsJsonObject().getString("description"));

        for (String token : List.of(adminToken, managerToken)) {
            assertTrue(ids(getItems("/api/maintenance?tenantId=" + tenant.tenantId(), token)).contains(issueId));
        }
        // The tenant sees the issue staff reported for them
        assertEquals(List.of(issueId), ids(getArray("/api/tenants/" + tenant.tenantId() + "/maintenance", tenant.token())));
    }

    @Test
    void filtersCanBeCombinedAndResultsAreNewestFirst() throws Exception {
        TenantLogin tenant = checkedInTenantWithLogin("Filter Tenant");
        String plumbingHigh = createIssue(tenant.token(), null, "Tap", "PLUMBING", "HIGH");
        String plumbingLow = createIssue(tenant.token(), null, "Drain", "PLUMBING", "LOW");
        String electricalHigh = createIssue(tenant.token(), null, "Switch", "ELECTRICAL", "HIGH");
        changeStatus(plumbingLow, "IN_PROGRESS", managerToken);
        String base = "/api/maintenance?tenantId=" + tenant.tenantId();

        assertEquals(List.of(electricalHigh, plumbingLow, plumbingHigh), ids(getItems(base, managerToken)));
        assertEquals(List.of(plumbingLow, plumbingHigh), ids(getItems(base + "&category=plumbing", managerToken)));
        assertEquals(List.of(electricalHigh, plumbingHigh), ids(getItems(base + "&priority=HIGH", managerToken)));
        assertEquals(List.of(plumbingHigh), ids(getItems(base + "&status=OPEN&priority=HIGH&category=PLUMBING", managerToken)));
        assertEquals(List.of(plumbingLow), ids(getItems(base + "&status=IN_PROGRESS", managerToken)));
        assertEquals(List.of(), ids(getItems(base + "&status=CLOSED", managerToken)));
        // Without a tenant filter, issues of other tenants are included too
        assertTrue(getItems("/api/maintenance?category=PLUMBING", managerToken).size() >= 2);
    }

    @Test
    void invalidInputGives400And404And409() throws Exception {
        TenantLogin tenant = checkedInTenantWithLogin("Validation Tenant");
        String issueId = createIssue(tenant.token(), null, "Light flickering", "ELECTRICAL", null);

        // 400: bad ids and values
        assertError(send(GET, "/api/maintenance/not-a-uuid", managerToken, null), 400, "BAD_REQUEST", "id must be a valid UUID");
        assertError(send(POST, "/api/maintenance", tenant.token(), issueJson(null, "Leak", "Leak", "GARDEN", null)),
                400, "BAD_REQUEST", "category must be PLUMBING, ELECTRICAL, CLEANING, FURNITURE, APPLIANCE, INTERNET or OTHER");
        assertError(send(POST, "/api/maintenance", tenant.token(), issueJson(null, "Leak", "Leak", "PLUMBING", "CRITICAL")),
                400, "BAD_REQUEST", "priority must be LOW, MEDIUM, HIGH or URGENT");
        assertError(send(POST, "/api/maintenance", tenant.token(), issueJson(null, " ", "Leak", "PLUMBING", null)),
                400, "BAD_REQUEST", "title is required");
        assertError(send(POST, "/api/maintenance", tenant.token(), issueJson(null, "Leak", "x".repeat(2001), "PLUMBING", null)),
                400, "BAD_REQUEST", "description must be at most 2000 characters");
        assertError(send(POST, "/api/maintenance", managerToken, issueJson("bad-id", "Leak", "Leak", "PLUMBING", null)),
                400, "BAD_REQUEST", "tenantId must be a valid UUID");
        assertError(send(PATCH, "/api/maintenance/" + issueId + "/status", managerToken, new JsonObject().put("status", "DONE")),
                400, "BAD_REQUEST", "status must be OPEN, IN_PROGRESS, RESOLVED or CLOSED");
        assertError(send(GET, "/api/maintenance?status=DONE", managerToken, null),
                400, "BAD_REQUEST", "status must be OPEN, IN_PROGRESS, RESOLVED or CLOSED");
        assertError(send(PATCH, "/api/maintenance/" + issueId + "/assign", managerToken, new JsonObject().put("assignedTo", "bad-id")),
                400, "BAD_REQUEST", "assignedTo must be a valid UUID");
        String tenantUserId = getJson("/api/auth/me", tenant.token()).getString("id");
        assertError(send(PATCH, "/api/maintenance/" + issueId + "/assign", managerToken, new JsonObject().put("assignedTo", tenantUserId)),
                400, "BAD_REQUEST", "Issues can only be assigned to an ADMIN or MANAGER");

        // 404: unknown issue, tenant and user
        assertError(send(GET, "/api/maintenance/" + UNKNOWN_ID, managerToken, null), 404, "NOT_FOUND", "Maintenance issue not found");
        assertError(send(PATCH, "/api/maintenance/" + UNKNOWN_ID + "/status", managerToken, new JsonObject().put("status", "RESOLVED")),
                404, "NOT_FOUND", "Maintenance issue not found");
        assertError(send(POST, "/api/maintenance", managerToken, issueJson(UNKNOWN_ID, "Leak", "Leak", "PLUMBING", null)),
                404, "NOT_FOUND", "Tenant not found");
        assertError(send(GET, "/api/tenants/" + UNKNOWN_ID + "/maintenance", managerToken, null), 404, "NOT_FOUND", "Tenant not found");
        assertError(send(PATCH, "/api/maintenance/" + issueId + "/assign", managerToken, new JsonObject().put("assignedTo", UNKNOWN_ID)),
                404, "NOT_FOUND", "Assigned user not found");

        // 409: impossible status moves, and a tenant who is not checked in
        assertError(send(PATCH, "/api/maintenance/" + issueId + "/status", managerToken, new JsonObject().put("status", "CLOSED")),
                409, "CONFLICT", "Cannot change status from OPEN to CLOSED");
        assertError(send(PATCH, "/api/maintenance/" + issueId + "/status", managerToken, new JsonObject().put("status", "OPEN")),
                409, "CONFLICT", "Issue is already OPEN");
        String notCheckedIn = createTenant("Not Checked In");
        assertError(send(POST, "/api/maintenance", managerToken, issueJson(notCheckedIn, "Leak", "Leak", "PLUMBING", null)),
                409, "CONFLICT", "Tenant must be checked in to a bed to report an issue");
    }

    @Test
    void tenantWithMaintenanceHistoryCannotBeDeletedButOneWithOnlyALoginCan() throws Exception {
        // Only a maintenance issue references this tenant (inserted directly: through the API it would also need a stay)
        String tenantId = createTenant("History Tenant");
        String bedId = createBed();
        Pool db = Database.createPool(vertx, databaseConfig());
        try {
            await(db.preparedQuery("INSERT INTO maintenance_issues (tenant_id, bed_id, title, description, category) VALUES ($1, $2, 'Leak', 'Leak', 'PLUMBING')")
                    .execute(Tuple.of(UUID.fromString(tenantId), UUID.fromString(bedId))));
        } finally {
            await(db.close());
        }
        assertError(send(DELETE, "/api/tenants/" + tenantId, managerToken, null),
                409, "CONFLICT", "Tenant cannot be deleted because they have occupancy, payment or maintenance history");

        // A tenant whose only link is a login account can be deleted, and the login goes with them
        String withLogin = createTenant("Login Only Tenant");
        String email = uniqueEmail();
        createAndGetId("/api/tenants/" + withLogin + "/account", new JsonObject().put("email", email).put("password", "password123"));
        assertEquals(204, send(DELETE, "/api/tenants/" + withLogin, managerToken, null).statusCode());
        assertError(login(email, "password123"), 401, "UNAUTHORIZED", "Invalid email or password");
    }

    /** The service validates first, but the database constraints also refuse bad data on their own. */
    @Test
    void databaseConstraintsRejectInvalidIssuesAndAccounts() throws Exception {
        UUID tenantId = UUID.fromString(createTenant("Constraint Tenant"));
        UUID bedId = UUID.fromString(createBed());
        Pool db = Database.createPool(vertx, databaseConfig());
        String insert = "INSERT INTO maintenance_issues (tenant_id, bed_id, title, description, category, priority, status, resolved_at) "
                + "VALUES ($1, $2, $3, $4, $5, $6, $7, $8)";
        String users = "INSERT INTO users (name, email, password_hash, role, tenant_id) VALUES ('X', $1, 'hash', $2, $3)";
        try {
            assertNotNull(awaitFailure(db.preparedQuery(insert).execute(Tuple.of(tenantId, bedId, " ", "Leak", "PLUMBING", "LOW", "OPEN", null))), "title must not be blank");
            assertNotNull(awaitFailure(db.preparedQuery(insert).execute(Tuple.of(tenantId, bedId, "Leak", "x".repeat(2001), "PLUMBING", "LOW", "OPEN", null))), "description length");
            assertNotNull(awaitFailure(db.preparedQuery(insert).execute(Tuple.of(tenantId, bedId, "Leak", "Leak", "GARDEN", "LOW", "OPEN", null))), "valid category");
            assertNotNull(awaitFailure(db.preparedQuery(insert).execute(Tuple.of(tenantId, bedId, "Leak", "Leak", "PLUMBING", "CRITICAL", "OPEN", null))), "valid priority");
            assertNotNull(awaitFailure(db.preparedQuery(insert).execute(Tuple.of(tenantId, bedId, "Leak", "Leak", "PLUMBING", "LOW", "DONE", null))), "valid status");
            assertNotNull(awaitFailure(db.preparedQuery(insert).execute(Tuple.of(tenantId, bedId, "Leak", "Leak", "PLUMBING", "LOW", "RESOLVED", null))), "RESOLVED needs resolved_at");
            assertNotNull(awaitFailure(db.preparedQuery(insert).execute(Tuple.of(UUID.randomUUID(), bedId, "Leak", "Leak", "PLUMBING", "LOW", "OPEN", null))), "tenant must exist");

            assertNotNull(awaitFailure(db.preparedQuery(users).execute(Tuple.of(uniqueEmail(), "TENANT", null))), "TENANT needs a tenant");
            assertNotNull(awaitFailure(db.preparedQuery(users).execute(Tuple.of(uniqueEmail(), "ADMIN", tenantId))), "staff has no tenant");
        } finally {
            await(db.close());
        }
    }

    // ---------- helpers ----------

    /** Property, room and bed, a tenant checked in to that bed, and a login for the tenant. */
    private static TenantLogin checkedInTenantWithLogin(String name) throws Exception {
        String bedId = createBed();
        JsonObject bed = getJson("/api/beds/" + bedId, managerToken);
        String roomId = bed.getString("roomId");
        String propertyId = getJson("/api/rooms/" + roomId, managerToken).getString("propertyId");

        String tenantId = createTenant(name);
        HttpResponse<Buffer> checkIn = send(POST, "/api/tenants/" + tenantId + "/check-in", managerToken, new JsonObject().put("bedId", bedId));
        assertEquals(200, checkIn.statusCode(), checkIn::bodyAsString);

        String email = uniqueEmail();
        createAndGetId("/api/tenants/" + tenantId + "/account", new JsonObject().put("email", email).put("password", "password123"));
        String token = login(email, "password123").bodyAsJsonObject().getString("token");
        return new TenantLogin(tenantId, bedId, roomId, propertyId, token);
    }

    private static String createIssue(String token, String tenantId, String title, String category, String priority) throws Exception {
        HttpResponse<Buffer> response = send(POST, "/api/maintenance", token, issueJson(tenantId, title, title + " - details", category, priority));
        assertEquals(201, response.statusCode(), () -> "Setup request failed: " + response.bodyAsString());
        return response.bodyAsJsonObject().getString("id");
    }

    private static JsonObject issueJson(String tenantId, String title, String description, String category, String priority) {
        JsonObject body = new JsonObject().put("title", title).put("description", description).put("category", category);
        if (tenantId != null) {
            body.put("tenantId", tenantId);
        }
        if (priority != null) {
            body.put("priority", priority);
        }
        return body;
    }

    private static JsonObject changeStatus(String issueId, String status, String token) throws Exception {
        HttpResponse<Buffer> response = send(PATCH, "/api/maintenance/" + issueId + "/status", token, new JsonObject().put("status", status));
        assertEquals(200, response.statusCode(), response::bodyAsString);
        return response.bodyAsJsonObject();
    }

    private static String createTenant(String name) throws Exception {
        return createAndGetId("/api/tenants", new JsonObject().put("name", name).put("phone", "9876543210")
                .put("joiningDate", "2026-08-01").put("monthlyRent", 8000).put("securityDeposit", 10000));
    }

    private static String createBed() throws Exception {
        String propertyId = createAndGetId("/api/properties",
                new JsonObject().put("name", "Maintenance Test PG").put("address", "5 Lake Road").put("city", "Pune"));
        String roomId = createAndGetId("/api/properties/" + propertyId + "/rooms", new JsonObject().put("roomNumber", "201").put("capacity", 1));
        return createAndGetId("/api/rooms/" + roomId + "/beds", new JsonObject().put("bedNumber", "A"));
    }

    private static List<String> ids(JsonArray array) {
        return array.stream().map(item -> ((JsonObject) item).getString("id")).toList();
    }

    private static String createAndGetId(String path, JsonObject body) throws Exception {
        HttpResponse<Buffer> response = send(POST, path, managerToken, body);
        assertEquals(201, response.statusCode(), () -> "Setup request failed: " + response.bodyAsString());
        return response.bodyAsJsonObject().getString("id");
    }

    private static JsonObject getJson(String path, String token) throws Exception {
        HttpResponse<Buffer> response = send(GET, path, token, null);
        assertEquals(200, response.statusCode(), () -> "GET " + path + " failed: " + response.bodyAsString());
        return response.bodyAsJsonObject();
    }

    /** The items of a paged list (GET /api/maintenance). Large page, so every matching issue is on it. */
    private static JsonArray getItems(String path, String token) throws Exception {
        return getJson(path + (path.contains("?") ? "&" : "?") + "size=100", token).getJsonArray("items");
    }

    private static JsonArray getArray(String path, String token) throws Exception {
        HttpResponse<Buffer> response = send(GET, path, token, null);
        assertEquals(200, response.statusCode(), () -> "GET " + path + " failed: " + response.bodyAsString());
        return response.bodyAsJsonArray();
    }
}
