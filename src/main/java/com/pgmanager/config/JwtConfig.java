package com.pgmanager.config;

import java.util.List;
import java.util.Locale;

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

    /** Words found in sample secrets such as the one in .env.example; a random secret never contains them. */
    private static final List<String> PLACEHOLDER_WORDS =
            List.of("change", "replace", "example", "placeholder", "secret", "your", "default", "password");
    private static final int MIN_DISTINCT_CHARACTERS = 10;

    /**
     * True for secrets that are clearly not random: sample values (they contain words like "change" or
     * "secret") or strings with very few different characters ("aaaa...", "12121212..."). Used to stop a
     * production deployment from starting with a copied placeholder. It is a sanity check, not a strength meter.
     */
    public static boolean looksWeak(String secret) {
        String lower = secret.toLowerCase(Locale.ROOT);
        return PLACEHOLDER_WORDS.stream().anyMatch(lower::contains)
                || secret.chars().distinct().count() < MIN_DISTINCT_CHARACTERS;
    }

    @Override
    public String toString() {
        return "JwtConfig[secret=***, expirationSeconds=%d]".formatted(expirationSeconds);
    }
}
