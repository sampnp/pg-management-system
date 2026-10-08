package com.pgmanager;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static com.pgmanager.TestFutures.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** End-to-end tests for registration, login, JWT middleware, role checks and admin role changes. */
class AuthApiIntegrationTest extends ApiTestBase {

    // ---------- registration ----------

    @Test
    void registerReturns201AndNeverExposesThePassword() throws Exception {
        String email = uniqueEmail();

        HttpResponse<Buffer> response = register("Sambit", email, "password123");

        assertEquals(201, response.statusCode());
        JsonObject body = response.bodyAsJsonObject();
        assertNotNull(body.getString("id"));
        assertEquals("Sambit", body.getString("name"));
        assertEquals(email, body.getString("email"));
        assertEquals("MANAGER", body.getString("role"));
        assertFalse(body.containsKey("password"));
        assertFalse(body.containsKey("passwordHash"));
        assertFalse(response.bodyAsString().contains("password123"));
    }

    @Test
    void registerWithDuplicateEmailReturns409() throws Exception {
        String email = uniqueEmail();
        register("Sambit", email, "password123");

        HttpResponse<Buffer> response = register("Someone Else", email, "password456");

        assertError(response, 409, "CONFLICT", "Email already exists");
    }

    @Test
    void registerWithInvalidEmailReturns400() throws Exception {
        assertError(register("Sambit", "not-an-email", "password123"), 400, "BAD_REQUEST", "email is not valid");
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        HttpResponse<Buffer> response = await(client.post("/api/auth/register")
                .putHeader("Content-Type", "application/json")
                .sendBuffer(Buffer.buffer("{ this is not json")));

        assertError(response, 400, "BAD_REQUEST", "Malformed JSON request body");
    }

    // ---------- login ----------

    @Test
    void loginReturnsTokenThatWorksForMe() throws Exception {
        String email = uniqueEmail();
        register("Sambit", email, "password123");
        String token = login(email, "password123").bodyAsJsonObject().getString("token");

        HttpResponse<Buffer> me = send(HttpMethod.GET, "/api/auth/me", token, null);

        assertEquals(200, me.statusCode());
        assertEquals(email, me.bodyAsJsonObject().getString("email"));
        assertEquals("MANAGER", me.bodyAsJsonObject().getString("role"));
        assertNotNull(me.bodyAsJsonObject().getString("id"));
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        String email = uniqueEmail();
        register("Sambit", email, "password123");

        assertError(login(email, "wrong-password"), 401, "UNAUTHORIZED", "Invalid email or password");
    }

    @Test
    void loginWithUnknownEmailReturnsTheSame401() throws Exception {
        assertError(login(uniqueEmail(), "password123"), 401, "UNAUTHORIZED", "Invalid email or password");
    }

    // ---------- authentication & authorization ----------

    @Test
    void meWithoutTokenReturns401() throws Exception {
        assertError(send(HttpMethod.GET, "/api/auth/me", null, null), 401, "UNAUTHORIZED", "Missing or invalid Authorization header");
    }

    @Test
    void meWithInvalidTokenReturns401() throws Exception {
        assertError(send(HttpMethod.GET, "/api/auth/me", "abc.def.ghi", null), 401, "UNAUTHORIZED", "Invalid or expired token");
    }

    @Test
    void adminCanAccessAdminEndpoint() throws Exception {
        HttpResponse<Buffer> response = send(HttpMethod.GET, "/api/admin/test", registerAndLogin("ADMIN"), null);

        assertEquals(200, response.statusCode());
    }

    @Test
    void managerGets403FromAdminEndpoint() throws Exception {
        HttpResponse<Buffer> response = send(HttpMethod.GET, "/api/admin/test", registerAndLogin("MANAGER"), null);

        assertError(response, 403, "FORBIDDEN", "Insufficient permissions");
    }

    // ---------- ADMIN can only be given by an ADMIN ----------

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "admin", " Admin "})
    void clientChosenAdminRoleIsIgnoredOnRegistration(String role) throws Exception {
        String email = uniqueEmail();
        JsonObject body = new JsonObject().put("name", "Mallory").put("email", email).put("password", "password123").put("role", role);

        HttpResponse<Buffer> response = send(HttpMethod.POST, "/api/auth/register", null, body);

        assertEquals(201, response.statusCode());
        assertEquals("MANAGER", response.bodyAsJsonObject().getString("role"));
        String token = login(email, "password123").bodyAsJsonObject().getString("token");
        assertEquals("MANAGER", send(HttpMethod.GET, "/api/auth/me", token, null).bodyAsJsonObject().getString("role"));
        assertError(send(HttpMethod.GET, "/api/admin/test", token, null), 403, "FORBIDDEN", "Insufficient permissions");
    }

    @Test
    void promoteWithoutTokenReturns401() throws Exception {
        String userId = registerUser(uniqueEmail());

        assertError(send(HttpMethod.PATCH, "/api/admin/users/" + userId + "/role", null, adminRole()),
                401, "UNAUTHORIZED", "Missing or invalid Authorization header");
    }

    @Test
    void managerCannotPromoteAnotherUserOrThemselves() throws Exception {
        String email = uniqueEmail();
        String managerId = registerUser(email);
        String managerToken = login(email, "password123").bodyAsJsonObject().getString("token");
        String otherId = registerUser(uniqueEmail());

        for (String userId : new String[] {otherId, managerId}) {
            assertError(send(HttpMethod.PATCH, "/api/admin/users/" + userId + "/role", managerToken, adminRole()),
                    403, "FORBIDDEN", "Insufficient permissions");
        }
        // Still a manager after logging in again
        String newToken = login(email, "password123").bodyAsJsonObject().getString("token");
        assertEquals("MANAGER", send(HttpMethod.GET, "/api/auth/me", newToken, null).bodyAsJsonObject().getString("role"));
    }

    @Test
    void adminCanPromoteManagerAndTheNewTokenCarriesTheAdminRole() throws Exception {
        String email = uniqueEmail();
        String userId = registerUser(email);
        String oldToken = login(email, "password123").bodyAsJsonObject().getString("token");

        HttpResponse<Buffer> response = send(HttpMethod.PATCH, "/api/admin/users/" + userId + "/role", registerAndLogin("ADMIN"), adminRole());

        assertEquals(200, response.statusCode());
        assertEquals("ADMIN", response.bodyAsJsonObject().getString("role"));
        assertFalse(response.bodyAsJsonObject().containsKey("passwordHash"));

        String newToken = login(email, "password123").bodyAsJsonObject().getString("token");
        assertEquals("ADMIN", send(HttpMethod.GET, "/api/auth/me", newToken, null).bodyAsJsonObject().getString("role"));
        assertEquals(200, send(HttpMethod.GET, "/api/admin/test", newToken, null).statusCode());
        // A JWT is not changed after it is issued: the old token keeps the old role until it expires
        assertEquals(403, send(HttpMethod.GET, "/api/admin/test", oldToken, null).statusCode());
    }

    @Test
    void adminCanChangeAnAdminBackToManager() throws Exception {
        String email = uniqueEmail();
        String userId = registerUser(email);
        setRoleInDatabase(email, "ADMIN");

        HttpResponse<Buffer> response = send(HttpMethod.PATCH, "/api/admin/users/" + userId + "/role",
                registerAndLogin("ADMIN"), new JsonObject().put("role", "manager"));

        assertEquals(200, response.statusCode());
        assertEquals("MANAGER", response.bodyAsJsonObject().getString("role"));
    }

    @Test
    void adminCannotChangeTheirOwnRole() throws Exception {
        String adminToken = registerAndLogin("ADMIN");
        String adminId = send(HttpMethod.GET, "/api/auth/me", adminToken, null).bodyAsJsonObject().getString("id");

        assertError(send(HttpMethod.PATCH, "/api/admin/users/" + adminId + "/role", adminToken, new JsonObject().put("role", "MANAGER")),
                400, "BAD_REQUEST", "You cannot change your own role");
    }

    @Test
    void invalidRoleChangesReturn400Or404() throws Exception {
        String adminToken = registerAndLogin("ADMIN");
        String userId = registerUser(uniqueEmail());

        assertError(send(HttpMethod.PATCH, "/api/admin/users/" + userId + "/role", adminToken, new JsonObject().put("role", "OWNER")),
                400, "BAD_REQUEST", "role must be ADMIN or MANAGER");
        assertError(send(HttpMethod.PATCH, "/api/admin/users/" + userId + "/role", adminToken, new JsonObject()),
                400, "BAD_REQUEST", "role is required");
        assertError(send(HttpMethod.PATCH, "/api/admin/users/not-a-uuid/role", adminToken, adminRole()),
                400, "BAD_REQUEST", "id must be a valid UUID");
        assertError(send(HttpMethod.PATCH, "/api/admin/users/" + UUID.randomUUID() + "/role", adminToken, adminRole()),
                404, "NOT_FOUND", "User not found");
    }

    // ---------- staff accounts created by an ADMIN ----------

    @Test
    void adminCreatesStaffAccountsThatCanLogIn() throws Exception {
        String adminToken = registerAndLogin("ADMIN");
        for (String role : new String[] {"MANAGER", "ADMIN"}) {
            String email = uniqueEmail();
            HttpResponse<Buffer> created = send(HttpMethod.POST, "/api/admin/users", adminToken, new JsonObject()
                    .put("name", "New " + role).put("email", email).put("password", "password123").put("role", role));

            assertEquals(201, created.statusCode(), created::bodyAsString);
            assertEquals(role, created.bodyAsJsonObject().getString("role"));
            assertFalse(created.bodyAsString().contains("password123"));
            String token = login(email, "password123").bodyAsJsonObject().getString("token");
            assertEquals(role, send(HttpMethod.GET, "/api/auth/me", token, null).bodyAsJsonObject().getString("role"));
        }
    }

    @Test
    void onlyAnAdminCanCreateStaffAccounts() throws Exception {
        JsonObject body = new JsonObject().put("name", "Sneaky").put("email", uniqueEmail()).put("password", "password123").put("role", "ADMIN");

        assertError(send(HttpMethod.POST, "/api/admin/users", null, body), 401, "UNAUTHORIZED", "Missing or invalid Authorization header");
        assertError(send(HttpMethod.POST, "/api/admin/users", registerAndLogin("MANAGER"), body), 403, "FORBIDDEN", "Insufficient permissions");
    }

    @Test
    void invalidStaffAccountsAreRejected() throws Exception {
        String adminToken = registerAndLogin("ADMIN");
        String takenEmail = uniqueEmail();
        register("Taken", takenEmail, "password123");

        assertError(send(HttpMethod.POST, "/api/admin/users", adminToken, new JsonObject()
                        .put("name", "Ravi").put("email", uniqueEmail()).put("password", "password123").put("role", "TENANT")),
                400, "BAD_REQUEST", "role must be ADMIN or MANAGER");
        assertError(send(HttpMethod.POST, "/api/admin/users", adminToken, new JsonObject()
                        .put("name", "Ravi").put("email", uniqueEmail()).put("password", "short").put("role", "MANAGER")),
                400, "BAD_REQUEST", "password must be at least 8 characters");
        assertError(send(HttpMethod.POST, "/api/admin/users", adminToken, new JsonObject()
                        .put("name", "Ravi").put("email", takenEmail).put("password", "password123").put("role", "MANAGER")),
                409, "CONFLICT", "Email already exists");
    }

    // ---------- existing behaviour ----------

    @Test
    void healthStillReportsUp() throws Exception {
        HttpResponse<Buffer> response = send(HttpMethod.GET, "/api/health", null, null);

        assertEquals(200, response.statusCode());
        assertEquals(new JsonObject().put("status", "UP").put("database", "UP"), response.bodyAsJsonObject());
    }

    @Test
    void unknownRouteReturnsJson404() throws Exception {
        assertError(send(HttpMethod.GET, "/api/does-not-exist", null, null), 404, "NOT_FOUND", "Resource not found");
    }

    private static String registerUser(String email) throws Exception {
        HttpResponse<Buffer> response = register("Test User", email, "password123");
        assertEquals(201, response.statusCode());
        return response.bodyAsJsonObject().getString("id");
    }

    private static JsonObject adminRole() {
        return new JsonObject().put("role", "ADMIN");
    }
}
