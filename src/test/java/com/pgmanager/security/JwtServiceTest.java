package com.pgmanager.security;

import com.pgmanager.config.JwtConfig;
import com.pgmanager.model.Role;
import com.pgmanager.model.User;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static com.pgmanager.TestFutures.await;
import static com.pgmanager.TestFutures.awaitFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-that-is-at-least-32-chars";
    private static final int EXPIRATION_SECONDS = 3600;

    private static Vertx vertx;
    private static JwtService jwtService;

    private final User user = new User(UUID.randomUUID(), "Sambit", "sambit@example.com", "hash", Role.MANAGER, Instant.now());

    @BeforeAll
    static void setUp() {
        vertx = Vertx.vertx();
        jwtService = new JwtService(vertx, new JwtConfig(SECRET, EXPIRATION_SECONDS));
    }

    @AfterAll
    static void tearDown() throws Exception {
        await(vertx.close());
    }

    @Test
    void validTokenIsVerifiedBackToTheSameUser() throws Exception {
        String token = jwtService.generateToken(user);

        AuthUser authUser = await(jwtService.verify(token));

        assertEquals(user.id(), authUser.id());
        assertEquals("sambit@example.com", authUser.email());
        assertEquals(Role.MANAGER, authUser.role());
    }

    @Test
    void tokenContainsExpectedClaims() {
        JsonObject claims = decodePayload(jwtService.generateToken(user));

        assertEquals(user.id().toString(), claims.getString("sub"));
        assertEquals("sambit@example.com", claims.getString("email"));
        assertEquals("MANAGER", claims.getString("role"));
        assertNotNull(claims.getLong("iat"));
        assertEquals(EXPIRATION_SECONDS, claims.getLong("exp") - claims.getLong("iat"));
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        long now = Instant.now().getEpochSecond();
        JsonObject claims = new JsonObject()
                .put("sub", user.id().toString())
                .put("email", user.email())
                .put("role", "MANAGER")
                .put("iat", now - 7200)
                .put("exp", now - 3600);
        // Signed with the correct secret, so expiry is the only thing wrong with it
        String expiredToken = signerWithSecret(SECRET)
                .generateToken(claims, new JWTOptions().setAlgorithm("HS256").setNoTimestamp(true));

        awaitFailure(jwtService.verify(expiredToken));
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() throws Exception {
        String forged = signerWithSecret("a-completely-different-secret-of-32-chars")
                .generateToken(new JsonObject().put("sub", user.id().toString()).put("email", user.email()).put("role", "ADMIN"),
                        new JWTOptions().setAlgorithm("HS256").setExpiresInSeconds(60));

        awaitFailure(jwtService.verify(forged));
    }

    @Test
    void tamperedPayloadIsRejected() throws Exception {
        String[] parts = jwtService.generateToken(user).split("\\.");
        JsonObject payload = decodePayload(String.join(".", parts)).put("role", "ADMIN");
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.encode().getBytes(StandardCharsets.UTF_8));

        awaitFailure(jwtService.verify(parts[0] + "." + tamperedPayload + "." + parts[2]));
    }

    @Test
    void garbageTokenIsRejected() throws Exception {
        awaitFailure(jwtService.verify("not-a-jwt"));
    }

    private static JWTAuth signerWithSecret(String secret) {
        return JWTAuth.create(vertx, new JWTAuthOptions()
                .addPubSecKey(new PubSecKeyOptions().setAlgorithm("HS256").setBuffer(secret)));
    }

    private static JsonObject decodePayload(String token) {
        String payload = token.split("\\.")[1];
        return new JsonObject(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
    }
}
