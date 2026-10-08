package com.pgmanager.security;

import at.favre.lib.crypto.bcrypt.BCrypt;

import java.nio.charset.StandardCharsets;

/**
 * BCrypt password hashing. Each hash has its own random salt, and the cost factor makes
 * brute-forcing slow. That slowness also means these methods are BLOCKING (tens to hundreds of ms),
 * so callers must run them with vertx.executeBlocking(), never directly on the event loop.
 */
public class PasswordHasher {

    public static final int DEFAULT_COST = 12;
    /** BCrypt only uses the first 72 bytes of a password; longer input is rejected. */
    public static final int MAX_PASSWORD_BYTES = 72;

    private final int cost;

    public PasswordHasher(int cost) {
        this.cost = cost;
    }

    public String hash(String password) {
        return BCrypt.withDefaults().hashToString(cost, password.toCharArray());
    }

    public boolean matches(String password, String hash) {
        if (password == null || hash == null || exceedsMaxLength(password)) {
            return false;
        }
        return BCrypt.verifyer().verify(password.toCharArray(), hash).verified;
    }

    public static boolean exceedsMaxLength(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES;
    }
}
