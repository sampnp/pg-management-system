package com.pgmanager;

import com.pgmanager.config.AppConfig;
import com.pgmanager.config.Database;
import com.pgmanager.config.DatabaseConfig;
import com.pgmanager.config.JwtConfig;
import com.pgmanager.config.RedisConfig;
import com.pgmanager.config.SecurityConfig;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;

import static com.pgmanager.TestFutures.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Base class for end-to-end HTTP tests: the real MainVerticle (router, JWT middleware, services,
 * Flyway migrations) against a throwaway PostgreSQL and Redis started by Testcontainers. Requires Docker.
 */
abstract class ApiTestBase {

    // One container of each shared by all test classes (started once; Testcontainers removes them when the JVM exits)
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        postgres.start();
        redis.start();
    }

    protected static Vertx vertx;
    protected static WebClient client;

    @BeforeAll
    static void startApplication() throws Exception {
        int port = freePort();
        // The tests create their users through public registration, so it is switched on here
        AppConfig config = testConfig(port, databaseConfig(), new SecurityConfig(true, null, null));

        vertx = Vertx.vertx();
        await(vertx.deployVerticle(new MainVerticle(config)));
        client = WebClient.create(vertx, new WebClientOptions().setDefaultHost("localhost").setDefaultPort(port));
    }

    /** The configuration the tests use, with the given port, database and account settings. */
    protected static AppConfig testConfig(int port, DatabaseConfig database, SecurityConfig security) {
        return new AppConfig(port, database, new JwtConfig("integration-test-secret-at-least-32-chars", 3600),
                redisConfig(), security);
    }

    /** A second copy of the application, started with different settings (and stopped again by the test). */
    protected record TestApp(String deploymentId, WebClient client) {
    }

    protected static TestApp startApp(AppConfig config) throws Exception {
        String deploymentId = await(vertx.deployVerticle(new MainVerticle(config)));
        return new TestApp(deploymentId,
                WebClient.create(vertx, new WebClientOptions().setDefaultHost("localhost").setDefaultPort(config.httpPort())));
    }

    protected static void stopApp(TestApp app) throws Exception {
        app.client().close();
        await(vertx.undeploy(app.deploymentId()));
    }

    /** Creates a new, empty database in the test PostgreSQL, e.g. to check that all migrations run from scratch. */
    protected static DatabaseConfig createEmptyDatabase() throws Exception {
        String name = "test_" + UUID.randomUUID().toString().replace("-", "");
        Pool db = Database.createPool(vertx, databaseConfig());
        try {
            // A database name can't be a $1 parameter; this one is generated above, never user input
            await(db.query("CREATE DATABASE " + name).execute());
        } finally {
            await(db.close());
        }
        DatabaseConfig base = databaseConfig();
        return new DatabaseConfig(base.host(), base.port(), name, base.user(), base.password());
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

    /** The test Redis, with the same 60 second dashboard TTL as the default configuration. */
    protected static RedisConfig redisConfig() {
        return new RedisConfig(redis.getHost(), redis.getMappedPort(6379), 60);
    }

    /** Sends a request with an optional Bearer token and optional JSON body. */
    protected static HttpResponse<Buffer> send(HttpMethod method, String path, String token, JsonObject body) throws Exception {
        return send(client, method, path, token, body);
    }

    /** Same as send(...), to another copy of the application. */
    protected static HttpResponse<Buffer> send(WebClient webClient, HttpMethod method, String path, String token, JsonObject body)
            throws Exception {
        HttpRequest<Buffer> request = webClient.request(method, path);
        if (token != null) {
            request.putHeader("Authorization", "Bearer " + token);
        }
        return await(body != null ? request.sendJsonObject(body) : request.send());
    }

    /** Public registration - always creates a MANAGER. */
    protected static HttpResponse<Buffer> register(String name, String email, String password) throws Exception {
        JsonObject body = new JsonObject().put("name", name).put("email", email).put("password", password);
        return send(HttpMethod.POST, "/api/auth/register", null, body);
    }

    protected static HttpResponse<Buffer> login(String email, String password) throws Exception {
        return send(HttpMethod.POST, "/api/auth/login", null, new JsonObject().put("email", email).put("password", password));
    }

    /**
     * Registers a fresh user with the given role and returns their JWT. Registration only creates managers,
     * so an ADMIN is made the same way an operator makes the first admin: an UPDATE run directly on the database.
     */
    protected static String registerAndLogin(String role) throws Exception {
        String email = uniqueEmail();
        assertEquals(201, register("Test User", email, "password123").statusCode());
        if (role.equals("ADMIN")) {
            setRoleInDatabase(email, "ADMIN");
        }
        HttpResponse<Buffer> response = login(email, "password123");
        assertEquals(200, response.statusCode());
        return response.bodyAsJsonObject().getString("token");
    }

    protected static void setRoleInDatabase(String email, String role) throws Exception {
        Pool db = Database.createPool(vertx, databaseConfig());
        try {
            await(db.preparedQuery("UPDATE users SET role = $2 WHERE email = $1").execute(Tuple.of(email, role)));
        } finally {
            await(db.close());
        }
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

    protected static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
