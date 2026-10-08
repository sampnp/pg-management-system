package com.pgmanager;

import com.pgmanager.config.AppConfig;
import com.pgmanager.config.DatabaseConfig;
import com.pgmanager.config.JwtConfig;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;

import static com.pgmanager.TestFutures.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests: the real MainVerticle (router, middleware, services, Flyway migrations)
 * against a throwaway PostgreSQL started by Testcontainers. Requires Docker.
 */
@Testcontainers
class AuthApiIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    private static Vertx vertx;
    private static WebClient client;

    @BeforeAll
    static void startApplication() throws Exception {
        int port = freePort();
        AppConfig config = new AppConfig(
                port,
                new DatabaseConfig(postgres.getHost(), postgres.getMappedPort(5432), postgres.getDatabaseName(),
                        postgres.getUsername(), postgres.getPassword()),
                new JwtConfig("integration-test-secret-at-least-32-chars", 3600));

        vertx = Vertx.vertx();
        await(vertx.deployVerticle(new MainVerticle(config)));
        client = WebClient.create(vertx, new WebClientOptions().setDefaultHost("localhost").setDefaultPort(port));
    }

    @AfterAll
    static void stopApplication() throws Exception {
        await(vertx.close());
    }

    // ---------- registration ----------

    @Test
    void registerReturns201AndNeverExposesThePassword() throws Exception {
        String email = uniqueEmail();

        HttpResponse<Buffer> response = register("Sambit", email, "password123", "MANAGER");

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
        register("Sambit", email, "password123", "MANAGER");

        HttpResponse<Buffer> response = register("Someone Else", email, "password456", "MANAGER");

        assertError(response, 409, "CONFLICT", "Email already exists");
    }

    @Test
    void registerWithInvalidEmailReturns400() throws Exception {
        assertError(register("Sambit", "not-an-email", "password123", "MANAGER"), 400, "BAD_REQUEST", "email is not valid");
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
        register("Sambit", email, "password123", "MANAGER");
        String token = login(email, "password123").bodyAsJsonObject().getString("token");

        HttpResponse<Buffer> me = await(client.get("/api/auth/me").putHeader("Authorization", "Bearer " + token).send());

        assertEquals(200, me.statusCode());
        assertEquals(email, me.bodyAsJsonObject().getString("email"));
        assertEquals("MANAGER", me.bodyAsJsonObject().getString("role"));
        assertNotNull(me.bodyAsJsonObject().getString("id"));
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        String email = uniqueEmail();
        register("Sambit", email, "password123", "MANAGER");

        assertError(login(email, "wrong-password"), 401, "UNAUTHORIZED", "Invalid email or password");
    }

    @Test
    void loginWithUnknownEmailReturnsTheSame401() throws Exception {
        assertError(login(uniqueEmail(), "password123"), 401, "UNAUTHORIZED", "Invalid email or password");
    }

    // ---------- authentication & authorization ----------

    @Test
    void meWithoutTokenReturns401() throws Exception {
        assertError(await(client.get("/api/auth/me").send()), 401, "UNAUTHORIZED", "Missing or invalid Authorization header");
    }

    @Test
    void meWithInvalidTokenReturns401() throws Exception {
        HttpResponse<Buffer> response = await(client.get("/api/auth/me").putHeader("Authorization", "Bearer abc.def.ghi").send());

        assertError(response, 401, "UNAUTHORIZED", "Invalid or expired token");
    }

    @Test
    void adminCanAccessAdminEndpoint() throws Exception {
        String token = registerAndLogin("ADMIN");

        HttpResponse<Buffer> response = await(client.get("/api/admin/test").putHeader("Authorization", "Bearer " + token).send());

        assertEquals(200, response.statusCode());
    }

    @Test
    void managerGets403FromAdminEndpoint() throws Exception {
        String token = registerAndLogin("MANAGER");

        HttpResponse<Buffer> response = await(client.get("/api/admin/test").putHeader("Authorization", "Bearer " + token).send());

        assertError(response, 403, "FORBIDDEN", "Insufficient permissions");
    }

    // ---------- existing behaviour ----------

    @Test
    void healthStillReportsUp() throws Exception {
        HttpResponse<Buffer> response = await(client.get("/api/health").send());

        assertEquals(200, response.statusCode());
        assertEquals(new JsonObject().put("status", "UP").put("database", "UP"), response.bodyAsJsonObject());
    }

    @Test
    void unknownRouteReturnsJson404() throws Exception {
        assertError(await(client.get("/api/does-not-exist").send()), 404, "NOT_FOUND", "Resource not found");
    }

    // ---------- helpers ----------

    private static HttpResponse<Buffer> register(String name, String email, String password, String role) throws Exception {
        JsonObject body = new JsonObject().put("name", name).put("email", email).put("password", password).put("role", role);
        return await(client.post("/api/auth/register").sendJsonObject(body));
    }

    private static HttpResponse<Buffer> login(String email, String password) throws Exception {
        return await(client.post("/api/auth/login").sendJsonObject(new JsonObject().put("email", email).put("password", password)));
    }

    private static String registerAndLogin(String role) throws Exception {
        String email = uniqueEmail();
        register("Test User", email, "password123", role);
        HttpResponse<Buffer> response = login(email, "password123");
        assertEquals(200, response.statusCode());
        return response.bodyAsJsonObject().getString("token");
    }

    private static void assertError(HttpResponse<Buffer> response, int status, String error, String message) {
        assertEquals(status, response.statusCode());
        JsonObject body = response.bodyAsJsonObject();
        assertEquals(status, body.getInteger("status"));
        assertEquals(error, body.getString("error"));
        assertEquals(message, body.getString("message"));
        assertTrue(body.containsKey("timestamp"));
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
