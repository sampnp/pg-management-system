package com.pgmanager;

import com.pgmanager.config.AppConfig;
import com.pgmanager.config.DatabaseConfig;
import com.pgmanager.config.JwtConfig;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;

import static com.pgmanager.TestFutures.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Base class for end-to-end HTTP tests: the real MainVerticle (router, JWT middleware, services,
 * Flyway migrations) against a throwaway PostgreSQL started by Testcontainers. Requires Docker.
 */
abstract class ApiTestBase {

    // One container shared by all test classes (started once; Testcontainers removes it when the JVM exits)
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    static {
        postgres.start();
    }

    protected static Vertx vertx;
    protected static WebClient client;

    @BeforeAll
    static void startApplication() throws Exception {
        int port = freePort();
        AppConfig config = new AppConfig(
                port,
                databaseConfig(),
                new JwtConfig("integration-test-secret-at-least-32-chars", 3600));

        vertx = Vertx.vertx();
        await(vertx.deployVerticle(new MainVerticle(config)));
        client = WebClient.create(vertx, new WebClientOptions().setDefaultHost("localhost").setDefaultPort(port));
    }

    @AfterAll
    static void stopApplication() throws Exception {
        await(vertx.close());
    }

    /** Connection details of the test PostgreSQL, for tests that need to run SQL directly. */
    protected static DatabaseConfig databaseConfig() {
        return new DatabaseConfig(postgres.getHost(), postgres.getMappedPort(5432), postgres.getDatabaseName(),
                postgres.getUsername(), postgres.getPassword());
    }

    /** Sends a request with an optional Bearer token and optional JSON body. */
    protected static HttpResponse<Buffer> send(HttpMethod method, String path, String token, JsonObject body) throws Exception {
        HttpRequest<Buffer> request = client.request(method, path);
        if (token != null) {
            request.putHeader("Authorization", "Bearer " + token);
        }
        return await(body != null ? request.sendJsonObject(body) : request.send());
    }

    protected static HttpResponse<Buffer> register(String name, String email, String password, String role) throws Exception {
        JsonObject body = new JsonObject().put("name", name).put("email", email).put("password", password).put("role", role);
        return send(HttpMethod.POST, "/api/auth/register", null, body);
    }

    protected static HttpResponse<Buffer> login(String email, String password) throws Exception {
        return send(HttpMethod.POST, "/api/auth/login", null, new JsonObject().put("email", email).put("password", password));
    }

    /** Registers a fresh user with the given role and returns their JWT. */
    protected static String registerAndLogin(String role) throws Exception {
        String email = uniqueEmail();
        register("Test User", email, "password123", role);
        HttpResponse<Buffer> response = login(email, "password123");
        assertEquals(200, response.statusCode());
        return response.bodyAsJsonObject().getString("token");
    }

    /** Checks the status code and the standard error body from GlobalErrorHandler. */
    protected static void assertError(HttpResponse<Buffer> response, int status, String error, String message) {
        assertEquals(status, response.statusCode(), () -> "Unexpected body: " + response.bodyAsString());
        JsonObject body = response.bodyAsJsonObject();
        assertEquals(status, body.getInteger("status"));
        assertEquals(error, body.getString("error"));
        assertEquals(message, body.getString("message"));
        assertTrue(body.containsKey("timestamp"));
    }

    protected static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
