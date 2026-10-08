package com.pgmanager.config;

/**
 * Application configuration, read once at startup from environment variables.
 * Secrets (passwords, JWT key) are never hard-coded; missing required values fail fast.
 */
public record AppConfig(int httpPort, DatabaseConfig database, JwtConfig jwt) {

    public static AppConfig fromEnv() {
        DatabaseConfig database = new DatabaseConfig(
                env("DATABASE_HOST", "localhost"),
                intEnv("DATABASE_PORT", 5432),
                env("DATABASE_NAME", "pg_manager"),
                requiredEnv("DATABASE_USER"),
                requiredEnv("DATABASE_PASSWORD"));

        JwtConfig jwt = new JwtConfig(
                requiredEnv("JWT_SECRET"),
                intEnv("JWT_EXPIRATION_SECONDS", 3600));

        return new AppConfig(intEnv("HTTP_PORT", 8080), database, jwt);
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + key);
        }
        return value;
    }

    private static int intEnv(String key, int defaultValue) {
        String value = env(key, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Environment variable " + key + " must be a number, got: " + value);
        }
    }
}
