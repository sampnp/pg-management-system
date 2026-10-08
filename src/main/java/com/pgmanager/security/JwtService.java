package com.pgmanager.security;

import com.pgmanager.config.JwtConfig;
import com.pgmanager.model.Role;
import com.pgmanager.model.User;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;

import java.util.UUID;

/**
 * Issues and verifies JWTs signed with HMAC-SHA256 (HS256) using vertx-auth-jwt.
 * Token claims: sub (user id), email, role, iat (issued at), exp (expiry).
 */
public class JwtService {

    private static final String ALGORITHM = "HS256";

    private final JWTAuth jwtAuth;
    private final int expirationSeconds;

    public JwtService(Vertx vertx, JwtConfig config) {
        this.jwtAuth = JWTAuth.create(vertx, new JWTAuthOptions()
                .addPubSecKey(new PubSecKeyOptions()
                        .setAlgorithm(ALGORITHM)
                        .setBuffer(config.secret())));
        this.expirationSeconds = config.expirationSeconds();
    }

    public String generateToken(User user) {
        JsonObject claims = new JsonObject()
                .put("email", user.email())
                .put("role", user.role().name());

        JWTOptions options = new JWTOptions()
                .setAlgorithm(ALGORITHM)
                .setSubject(user.id().toString())
                .setExpiresInSeconds(expirationSeconds);  // iat is added automatically

        return jwtAuth.generateToken(claims, options);
    }

    /**
     * Checks the signature and expiry. Fails if the token is malformed, tampered with, expired,
     * or missing the claims we need.
     */
    public Future<AuthUser> verify(String token) {
        return jwtAuth.authenticate(new TokenCredentials(token))
                .map(user -> {
                    JsonObject claims = user.principal();
                    return new AuthUser(
                            UUID.fromString(claims.getString("sub")),
                            claims.getString("email"),
                            Role.valueOf(claims.getString("role")));
                });
    }
}
