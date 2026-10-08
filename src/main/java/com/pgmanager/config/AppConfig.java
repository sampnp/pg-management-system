package com.pgmanager.config;

import java.util.Locale;
import java.util.Map;

/**
 * Application configuration, read once at startup from environment variables.
 * Secrets (passwords, JWT key) are never hard-coded; missing or unsafe values fail fast.
 */
public record AppConfig(int httpPort, DatabaseConfig database, JwtConfig jwt, RedisConfig redis, SecurityConfig security) {

    public static AppConfig fromEnv() {
        return from(System.getenv());
    }

    /** Reads the configuration from the given variables (System.getenv() normally, a plain map in tests). */
    static AppConfig from(Map<String, String> env) {
        // production is the default, so a deployment that forgets APP_ENV gets the strict checks
        String appEnv = get(env, "APP_ENV", "production").toLowerCase(Locale.ROOT);
        if (!appEnv.equals("production") && !appEnv.equals("development")) {
            throw new IllegalStateException("APP_ENV must be development or production, got: " + appEnv);
        }

        DatabaseConfig database = new DatabaseConfig(
                get(env, "DATABASE_HOST", "localhost"),
                getInt(env, "DATABASE_PORT", 5432),
                get(env, "DATABASE_NAME", "pg_manager"),
                required(env, "DATABASE_USER"),
                required(env, "DATABASE_PASSWORD"));

        JwtConfig jwt = new JwtConfig(
                required(env, "JWT_SECRET"),
                getInt(env, "JWT_EXPIRATION_SECONDS", 3600));
        if (appEnv.equals("production") && JwtConfig.looksWeak(jwt.secret())) {
            throw new IllegalStateException("JWT_SECRET looks like a placeholder or is not random enough. "
                    + "Use a long random value (for example: openssl rand -base64 48), "
                    + "or set APP_ENV=development for local development.");
        }

        RedisConfig redis = new RedisConfig(
                get(env, "REDIS_HOST", "localhost"),
                getInt(env, "REDIS_PORT", 6379),
                getInt(env, "DASHBOARD_CACHE_TTL_SECONDS", 60));

        SecurityConfig security = new SecurityConfig(
                getBoolean(env, "ALLOW_PUBLIC_REGISTRATION", false),
                env.get("BOOTSTRAP_ADMIN_EMAIL"),
                env.get("BOOTSTRAP_ADMIN_PASSWORD"));

        return new AppConfig(getInt(env, "HTTP_PORT", 8080), database, jwt, redis, security);
    }

    private static String get(Map<String, String> env, String key, String defaultValue) {
        String value = env.get(key);
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }

    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + key);
        }
        return value;
    }

    private static int getInt(Map<String, String> env, String key, int defaultValue) {
        String value = get(env, key, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Environment variable " + key + " must be a number, got: " + value);
        }
    }

    private static boolean getBoolean(Map<String, String> env, String key, boolean defaultValue) {
        String value = get(env, key, String.valueOf(defaultValue)).toLowerCase(Locale.ROOT);
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalStateException("Environment variable " + key + " must be true or false, got: " + value);
        };
    }
}
