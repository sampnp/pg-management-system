package com.pgmanager;

import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.pgmanager.TestFutures.await;
import static io.vertx.core.http.HttpMethod.GET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** X-Request-Id handling and the one-line-per-request log. */
class RequestLoggingIntegrationTest extends ApiTestBase {

    @Test
    void everyResponseGetsARequestId() throws Exception {
        HttpResponse<Buffer> ok = send(GET, "/api/health", null, null);
        HttpResponse<Buffer> unauthorized = send(GET, "/api/tenants", null, null);
        HttpResponse<Buffer> notFound = send(GET, "/api/nothing-here", null, null);

        for (HttpResponse<Buffer> response : new HttpResponse[] {ok, unauthorized, notFound}) {
            UUID.fromString(response.getHeader("X-Request-Id"));   // a generated UUID
        }
        assertNotEquals(ok.getHeader("X-Request-Id"), unauthorized.getHeader("X-Request-Id"));
    }

    @Test
    void safeIncomingRequestIdIsKeptAndUnsafeOneIsReplaced() throws Exception {
        HttpResponse<Buffer> kept = await(client.get("/api/health").putHeader("X-Request-Id", "proxy-123.abc_DEF").send());
        assertEquals("proxy-123.abc_DEF", kept.getHeader("X-Request-Id"));

        for (String unsafe : new String[] {"has spaces", "x".repeat(65), "line\tbreak", "<script>"}) {
            HttpResponse<Buffer> replaced = await(client.get("/api/health").putHeader("X-Request-Id", unsafe).send());
            UUID.fromString(replaced.getHeader("X-Request-Id"));
        }
    }

    @Test
    void requestLogLineHasTheDetailsButNoPasswordOrToken() throws Exception {
        String email = uniqueEmail();
        register("Logged User", email, "secret-pass-123");
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        String token;
        String requestId;
        try {
            token = login(email, "secret-pass-123").bodyAsJsonObject().getString("token");
            requestId = await(client.get("/api/auth/me").putHeader("Authorization", "Bearer " + token)
                    .putHeader("X-Request-Id", "trace-me-42").send()).getHeader("X-Request-Id");
            waitForLogLine(captured, "id=trace-me-42");
        } finally {
            System.setErr(originalErr);
        }
        String logs = captured.toString(StandardCharsets.UTF_8);

        assertEquals("trace-me-42", requestId);
        assertTrue(logs.contains("method=POST path=/api/auth/login status=200"), logs);
        String meLine = logs.lines().filter(l -> l.contains("id=trace-me-42")).findFirst().orElseThrow();
        assertTrue(meLine.matches(".*request id=trace-me-42 method=GET path=/api/auth/me status=200 durationMs=\\d+ user=[0-9a-f-]{36}.*"), meLine);
        assertFalse(logs.contains("secret-pass-123"), "password must never be logged");
        assertFalse(logs.contains(token), "JWT must never be logged");
        assertFalse(logs.contains("Bearer"), "Authorization header must never be logged");
        assertFalse(logs.contains(email), "the login body is not logged");
    }

    /** The log line is written when the response ends, which can be a moment after the client has it. */
    private static void waitForLogLine(ByteArrayOutputStream captured, String text) throws Exception {
        for (int i = 0; i < 50 && !captured.toString(StandardCharsets.UTF_8).contains(text); i++) {
            await(vertx.timer(20));
        }
    }
}
