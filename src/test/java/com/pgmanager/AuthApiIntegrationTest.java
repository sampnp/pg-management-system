package com.pgmanager;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.Test;

import static com.pgmanager.TestFutures.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** End-to-end tests for registration, login, JWT middleware and role checks. */
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
}
