package com.pgmanager;

import com.pgmanager.config.SecurityConfig;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.pgmanager.TestFutures.await;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.POST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Security headers, CORS, and error responses that never show internal details. */
class HttpHardeningIntegrationTest extends ApiTestBase {

    // ---------- security headers ----------

    @Test
    void everyResponseHasTheSecurityHeaders() throws Exception {
        for (HttpResponse<Buffer> response : new HttpResponse[] {
                send(GET, "/api/health", null, null),                  // 200
                send(GET, "/api/properties", null, null),              // 401
                send(GET, "/api/does-not-exist", null, null)}) {       // 404
            assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
            assertEquals("DENY", response.getHeader("X-Frame-Options"));
            assertEquals("default-src 'none'; frame-ancestors 'none'", response.getHeader("Content-Security-Policy"));
            assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
            assertEquals("no-store", response.getHeader("Cache-Control"));
        }
    }

    // ---------- CORS ----------

    @Test
    void noCorsHeadersByDefaultSoBrowsersBlockCrossSiteCalls() throws Exception {
        HttpResponse<Buffer> response = await(client.get("/api/health").putHeader("Origin", "https://evil.example.com").send());
        HttpResponse<Buffer> preflight = await(client.request(HttpMethod.OPTIONS, "/api/properties")
                .putHeader("Origin", "https://evil.example.com")
                .putHeader("Access-Control-Request-Method", "GET").send());

        assertNull(response.getHeader("Access-Control-Allow-Origin"));
        assertNull(preflight.getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void configuredOriginIsTheOnlyOneAllowed() throws Exception {
        String allowed = "https://app.example.com";
        TestApp app = startApp(testConfig(freePort(), databaseConfig(), new SecurityConfig(false, null, null, allowed)));
        try {
            HttpResponse<Buffer> preflight = await(app.client().request(HttpMethod.OPTIONS, "/api/properties")
                    .putHeader("Origin", allowed)
                    .putHeader("Access-Control-Request-Method", "POST")
                    .putHeader("Access-Control-Request-Headers", "Authorization, Content-Type").send());
            assertEquals(allowed, preflight.getHeader("Access-Control-Allow-Origin"));
            assertEquals(allowed, await(app.client().get("/api/health").putHeader("Origin", allowed).send())
                    .getHeader("Access-Control-Allow-Origin"));

            HttpResponse<Buffer> other = await(app.client().get("/api/health").putHeader("Origin", "https://evil.example.com").send());
            assertNull(other.getHeader("Access-Control-Allow-Origin"));
        } finally {
            stopApp(app);
        }
    }

    // ---------- error responses ----------

    @Test
    void clientErrorsUseTheStandardFormatWithoutInternalDetails() throws Exception {
        String token = registerAndLogin("MANAGER");

        HttpResponse<Buffer> malformed = await(client.post("/api/properties").putHeader("Authorization", "Bearer " + token)
                .putHeader("Content-Type", "application/json").sendBuffer(Buffer.buffer("{\"name\": ")));
        assertError(malformed, 400, "BAD_REQUEST", "Malformed JSON request body");

        // A number where text is expected / text where a number is expected
        assertError(send(POST, "/api/rooms/" + UUID.randomUUID() + "/beds", token, new JsonObject().put("bedNumber", new JsonObject())),
                400, "BAD_REQUEST", "Malformed JSON request body");
        assertError(send(POST, "/api/tenants", token, new JsonObject().put("name", "X").put("monthlyRent", "lots")),
                400, "BAD_REQUEST", "Malformed JSON request body");

        // Bodies over 64 KB are refused before any handler runs
        HttpResponse<Buffer> tooLarge = await(client.post("/api/properties").putHeader("Authorization", "Bearer " + token)
                .putHeader("Content-Type", "application/json")
                .sendBuffer(Buffer.buffer("{\"name\": \"" + "x".repeat(70 * 1024) + "\"}")));
        assertError(tooLarge, 413, "REQUEST_ENTITY_TOO_LARGE", "Request body too large");

        assertError(send(HttpMethod.DELETE, "/api/health", null, null), 405, "METHOD_NOT_ALLOWED", "Method not allowed");

        for (HttpResponse<Buffer> response : new HttpResponse[] {malformed, tooLarge}) {
            assertFalse(response.bodyAsString().contains("Exception"), response.bodyAsString());
            assertFalse(response.bodyAsString().contains("com.pgmanager"), response.bodyAsString());
            assertFalse(response.bodyAsString().contains("com.fasterxml"), response.bodyAsString());
        }
    }
}
