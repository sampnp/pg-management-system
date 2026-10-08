package com.pgmanager;

import com.pgmanager.config.Database;
import com.pgmanager.config.DatabaseConfig;
import com.pgmanager.config.SecurityConfig;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.pgmanager.TestFutures.await;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.POST;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A production-like start: a brand-new empty database, public registration off (the default), and the first
 * ADMIN created from BOOTSTRAP_ADMIN_* settings. Each test starts its own copy of the application.
 */
class AccountSetupIntegrationTest extends ApiTestBase {

    @Test
    void freshDatabaseWithRegistrationOffAndBootstrapAdmin() throws Exception {
        DatabaseConfig database = createEmptyDatabase();
        SecurityConfig security = new SecurityConfig(false, "Owner@Example.com", "owner-password-123");
        TestApp app = startApp(testConfig(freePort(), database, security));
        try {
            // Every migration ran on the empty database, the same ones as on the main test database
            assertEquals(200, send(app.client(), GET, "/api/health", null, null).statusCode());
            assertEquals(migrationVersions(databaseConfig()), migrationVersions(database));

            // Public sign-up is off
            assertError(send(app.client(), POST, "/api/auth/register", null,
                            new JsonObject().put("name", "Mallory").put("email", uniqueEmail()).put("password", "password123")),
                    403, "FORBIDDEN", "Public registration is disabled. Ask an administrator to create your account");

            // The bootstrap admin can log in...
            String adminToken = loginOn(app, "owner@example.com", "owner-password-123");
            assertEquals("ADMIN", send(app.client(), GET, "/api/auth/me", adminToken, null).bodyAsJsonObject().getString("role"));

            // ...and creates the staff accounts
            String managerEmail = uniqueEmail();
            assertEquals(201, send(app.client(), POST, "/api/admin/users", adminToken, new JsonObject()
                    .put("name", "Meena").put("email", managerEmail).put("password", "manager-pass-1").put("role", "MANAGER")).statusCode());
            String managerToken = loginOn(app, managerEmail, "manager-pass-1");
            assertEquals(200, send(app.client(), GET, "/api/properties", managerToken, null).statusCode());
        } finally {
            stopApp(app);
        }

        // Starting again with the same settings does not create a second admin
        TestApp restarted = startApp(testConfig(freePort(), database, security));
        stopApp(restarted);
        assertEquals(1, adminCount(database));
    }

    @Test
    void bootstrapNeverTakesOverAnExistingAccount() throws Exception {
        DatabaseConfig database = createEmptyDatabase();

        // Someone registers the address first (registration switched on for this step)
        TestApp open = startApp(testConfig(freePort(), database, new SecurityConfig(true, null, null)));
        try {
            assertEquals(201, send(open.client(), POST, "/api/auth/register", null, new JsonObject()
                    .put("name", "Early Bird").put("email", "owner@example.com").put("password", "password123")).statusCode());
        } finally {
            stopApp(open);
        }

        TestApp app = startApp(testConfig(freePort(), database, new SecurityConfig(false, "owner@example.com", "owner-password-123")));
        try {
            assertEquals(0, adminCount(database));
            String token = loginOn(app, "owner@example.com", "password123");
            assertEquals("MANAGER", send(app.client(), GET, "/api/auth/me", token, null).bodyAsJsonObject().getString("role"));
        } finally {
            stopApp(app);
        }
    }

    private static String loginOn(TestApp app, String email, String password) throws Exception {
        var response = send(app.client(), POST, "/api/auth/login", null, new JsonObject().put("email", email).put("password", password));
        assertEquals(200, response.statusCode(), response::bodyAsString);
        return response.bodyAsJsonObject().getString("token");
    }

    private static List<String> migrationVersions(DatabaseConfig database) throws Exception {
        Pool db = Database.createPool(vertx, database);
        try {
            List<String> versions = new ArrayList<>();
            for (Row row : await(db.query("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank").execute())) {
                versions.add(row.getString("version"));
            }
            return versions;
        } finally {
            await(db.close());
        }
    }

    private static long adminCount(DatabaseConfig database) throws Exception {
        Pool db = Database.createPool(vertx, database);
        try {
            return await(db.preparedQuery("SELECT count(*) FROM users WHERE role = $1").execute(Tuple.of("ADMIN")))
                    .iterator().next().getLong(0);
        } finally {
            await(db.close());
        }
    }
}
