package com.pgmanager.config;

public record JwtConfig(String secret, int expirationSeconds) {

    /** HS256 needs a key of at least 256 bits; shorter secrets are easy to brute-force. */
    private static final int MIN_SECRET_LENGTH = 32;

    // Compact constructor: runs before the fields are assigned, so an invalid config can never be created
    public JwtConfig {
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("JWT_SECRET must be at least " + MIN_SECRET_LENGTH + " characters long");
        }
        if (expirationSeconds <= 0) {
            throw new IllegalStateException("JWT_EXPIRATION_SECONDS must be positive");
        }
    }

    @Override
    public String toString() {
        return "JwtConfig[secret=***, expirationSeconds=%d]".formatted(expirationSeconds);
    }
}
