package com.pgmanager;

import com.pgmanager.config.SecurityConfig;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.Test;

import static com.pgmanager.TestFutures.await;
import static com.pgmanager.TestFutures.awaitFailure;
import static io.vertx.core.http.HttpMethod.GET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Stopping the application lets running requests finish and then closes everything. */
class GracefulShutdownIntegrationTest extends ApiTestBase {

    @Test
    void requestInProgressStillFinishesWhenTheAppStops() throws Exception {
        TestApp app = startApp(testConfig(freePort(), databaseConfig(), new SecurityConfig(true, null, null)));
        assertEquals(200, send(app.client(), GET, "/api/health", null, null).statusCode());

        // Registration hashes the password with BCrypt (a few hundred milliseconds), so it is still running...
        Future<HttpResponse<Buffer>> running = app.client().post("/api/auth/register").sendJsonObject(new JsonObject()
                .put("name", "Last Request").put("email", uniqueEmail()).put("password", "password123"));
        await(vertx.timer(100));

        // ...when the application is stopped
        Future<Void> stopped = vertx.undeploy(app.deploymentId());

        HttpResponse<Buffer> response = await(running);
        assertEquals(201, response.statusCode(), response::bodyAsString);
        await(stopped);

        // Afterwards the server no longer accepts requests
        assertNotNull(awaitFailure(app.client().get("/api/health").send()));
        app.client().close();
    }
}
