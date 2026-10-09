package com.pgmanager;

import com.pgmanager.config.AppConfig;
import com.pgmanager.config.RedisConfig;
import com.pgmanager.config.SecurityConfig;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.redis.client.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.pgmanager.TestFutures.await;
import static io.vertx.core.http.HttpMethod.POST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Login brute-force protection with a real Redis: 5 failures per IP + email in 15 minutes, then 429. */
class LoginRateLimitIntegrationTest extends ApiTestBase {

    /** Every test starts with no failure counters (they all come from the same test IP). */
    @BeforeEach
    void clearCounters() throws Exception {
        Response keys = await(redisApi.keys("auth:login:fail:*"));
        List<String> names = new ArrayList<>();
        keys.forEach(key -> names.add(key.toString()));
        if (!names.isEmpty()) {
            await(redisApi.del(names));
        }
    }

    @Test
    void fiveFailuresBlockThatEmailFromThisIpEvenWithTheRightPassword() throws Exception {
        String email = registered();
        for (int i = 0; i < 5; i++) {
            assertError(login(email, "wrong-password"), 401, "UNAUTHORIZED", "Invalid email or password");
        }

        HttpResponse<Buffer> blocked = login(email, "wrong-password");
        assertError(blocked, 429, "TOO_MANY_REQUESTS", "Too many failed login attempts. Try again later");
        long retryAfter = Long.parseLong(blocked.getHeader("Retry-After"));
        assertTrue(retryAfter > 0 && retryAfter <= 900, "Retry-After was " + retryAfter);

        // Even the right password is refused until the window ends (the email is not different in any way)
        assertError(login(email.toUpperCase(), "password123"), 429, "TOO_MANY_REQUESTS", "Too many failed login attempts. Try again later");

        // Another account from the same IP is not affected
        String other = registered();
        assertEquals(200, login(other, "password123").statusCode());
    }

    @Test
    void theBlockEndsWhenTheWindowExpires() throws Exception {
        String email = registered();
        for (int i = 0; i < 6; i++) {
            login(email, "wrong-password");
        }
        assertEquals(429, login(email, "password123").statusCode());

        // Simulate the 15 minutes passing: the counter's TTL runs out and Redis deletes it
        clearCounters();

        assertEquals(200, login(email, "password123").statusCode());
    }

    @Test
    void successfulLoginResetsTheCounter() throws Exception {
        String email = registered();
        for (int i = 0; i < 4; i++) {
            login(email, "wrong-password");
        }
        assertEquals(200, login(email, "password123").statusCode());

        // The four earlier failures are forgotten: four more are still answered normally
        for (int i = 0; i < 4; i++) {
            assertEquals(401, login(email, "wrong-password").statusCode());
        }
        assertEquals(200, login(email, "password123").statusCode());
    }

    @Test
    void counterHasAFifteenMinuteTtl() throws Exception {
        String email = registered();
        login(email, "wrong-password");

        Response keys = await(redisApi.keys("auth:login:fail:*"));
        assertEquals(1, keys.size());
        String key = keys.get(0).toString();
        assertEquals(1, await(redisApi.get(key)).toLong());
        long ttl = await(redisApi.ttl(key)).toLong();
        assertTrue(ttl > 890 && ttl <= 900, "TTL was " + ttl);
    }

    @Test
    void loginStillWorksWhenRedisIsDownButIsNotLimited() throws Exception {
        String email = registered();
        TestApp app = startAppWithDeadRedis();
        try {
            for (int i = 0; i < 7; i++) {
                HttpResponse<Buffer> response = send(app.client(), POST, "/api/auth/login", null,
                        new JsonObject().put("email", email).put("password", "wrong-password"));
                assertEquals(401, response.statusCode(), "fail-open: no 429 without Redis");
            }
            HttpResponse<Buffer> ok = send(app.client(), POST, "/api/auth/login", null,
                    new JsonObject().put("email", email).put("password", "password123"));
            assertEquals(200, ok.statusCode());
        } finally {
            stopApp(app);
        }
    }

    /** A second copy of the app whose Redis address points at a port where nothing listens. */
    private static TestApp startAppWithDeadRedis() throws Exception {
        AppConfig base = testConfig(freePort(), databaseConfig(), new SecurityConfig(true, null, null));
        return startApp(new AppConfig(base.httpPort(), base.database(), base.jwt(),
                new RedisConfig("localhost", freePort(), 60), base.security()));
    }

    private static String registered() throws Exception {
        String email = uniqueEmail();
        assertEquals(201, register("Rate Limited", email, "password123").statusCode());
        return email;
    }
}
