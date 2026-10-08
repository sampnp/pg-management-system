package com.pgmanager.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading and checking the environment configuration (AppConfig.from takes a map instead of the real environment). */
class AppConfigTest {

    /** Looks random enough for production. */
    private static final String STRONG_SECRET = "kX9#mQ2$vL7pR4tZ8wB1nC6yH3jF5dGs0aE";
    private static final String EXAMPLE_SECRET = "change-this-to-a-long-random-secret-of-at-least-32-chars";

    @Test
    void defaultsAreSafe() {
        AppConfig config = AppConfig.from(minimalEnv());

        assertEquals(8080, config.httpPort());
        assertEquals("localhost", config.database().host());
        assertEquals(5432, config.database().port());
        assertEquals("pg_manager", config.database().name());
        assertEquals(3600, config.jwt().expirationSeconds());
        assertEquals("localhost", config.redis().host());
        assertEquals(6379, config.redis().port());
        assertEquals(60, config.redis().dashboardCacheTtlSeconds());
        // Secure by default: no public sign-up, no bootstrap admin
        assertFalse(config.security().allowPublicRegistration());
        assertFalse(config.security().hasBootstrapAdmin());
    }

    @Test
    void everyValueCanBeConfigured() {
        Map<String, String> env = minimalEnv();
        env.put("HTTP_PORT", "9090");
        env.put("DATABASE_HOST", "postgres");
        env.put("DATABASE_PORT", "5433");
        env.put("DATABASE_NAME", "pg");
        env.put("JWT_EXPIRATION_SECONDS", "900");
        env.put("REDIS_HOST", "redis");
        env.put("REDIS_PORT", "6380");
        env.put("DASHBOARD_CACHE_TTL_SECONDS", "30");
        env.put("ALLOW_PUBLIC_REGISTRATION", "TRUE");
        env.put("BOOTSTRAP_ADMIN_EMAIL", "owner@example.com");
        env.put("BOOTSTRAP_ADMIN_PASSWORD", "a-strong-password");

        AppConfig config = AppConfig.from(env);

        assertEquals(9090, config.httpPort());
        assertEquals("postgres", config.database().host());
        assertEquals(5433, config.database().port());
        assertEquals("pg", config.database().name());
        assertEquals(900, config.jwt().expirationSeconds());
        assertEquals("redis:6380", config.redis().host() + ":" + config.redis().port());
        assertEquals(30, config.redis().dashboardCacheTtlSeconds());
        assertTrue(config.security().allowPublicRegistration());
        assertTrue(config.security().hasBootstrapAdmin());
        assertEquals("owner@example.com", config.security().bootstrapAdminEmail());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            EXAMPLE_SECRET,
            "replace_with_a_long_random_secret_at_least_32_chars",
            "my-super-secret-jwt-signing-key-1234567",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "12121212121212121212121212121212"})
    void productionRefusesPlaceholderOrNonRandomSecrets(String secret) {
        Map<String, String> env = minimalEnv();
        env.put("JWT_SECRET", secret);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> AppConfig.from(env));
        assertTrue(error.getMessage().startsWith("JWT_SECRET looks like a placeholder"), error.getMessage());
    }

    @Test
    void developmentAcceptsThePlaceholderSecret() {
        Map<String, String> env = minimalEnv();
        env.put("APP_ENV", "development");
        env.put("JWT_SECRET", EXAMPLE_SECRET);

        assertEquals(EXAMPLE_SECRET, AppConfig.from(env).jwt().secret());
    }

    @Test
    void shortSecretIsRefusedEvenInDevelopment() {
        Map<String, String> env = minimalEnv();
        env.put("APP_ENV", "development");
        env.put("JWT_SECRET", "too-short");

        assertThrows(IllegalStateException.class, () -> AppConfig.from(env));
    }

    @Test
    void missingRequiredVariableIsReportedByName() {
        Map<String, String> env = minimalEnv();
        env.remove("DATABASE_PASSWORD");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> AppConfig.from(env));
        assertEquals("Missing required environment variable: DATABASE_PASSWORD", error.getMessage());
    }

    @Test
    void invalidValuesAreRejected() {
        assertInvalid("APP_ENV", "staging", "APP_ENV must be development or production, got: staging");
        assertInvalid("ALLOW_PUBLIC_REGISTRATION", "yes", "Environment variable ALLOW_PUBLIC_REGISTRATION must be true or false, got: yes");
        assertInvalid("HTTP_PORT", "eighty", "Environment variable HTTP_PORT must be a number, got: eighty");
        assertInvalid("DASHBOARD_CACHE_TTL_SECONDS", "0", "DASHBOARD_CACHE_TTL_SECONDS must be positive");
        assertInvalid("BOOTSTRAP_ADMIN_EMAIL", "owner@example.com", "Set both BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD, or neither");
    }

    @Test
    void secretsAreNotPrintedByToString() {
        Map<String, String> env = minimalEnv();
        env.put("BOOTSTRAP_ADMIN_EMAIL", "owner@example.com");
        env.put("BOOTSTRAP_ADMIN_PASSWORD", "a-strong-password");
        AppConfig config = AppConfig.from(env);

        assertFalse(config.toString().contains(STRONG_SECRET));
        assertFalse(config.toString().contains("a-strong-password"));
        assertFalse(config.toString().contains("db-password"));
    }

    @Test
    void blankOptionalValuesCountAsNotSet() {
        Map<String, String> env = minimalEnv();
        env.put("REDIS_HOST", "  ");
        env.put("BOOTSTRAP_ADMIN_EMAIL", "");
        env.put("BOOTSTRAP_ADMIN_PASSWORD", "");

        AppConfig config = AppConfig.from(env);

        assertEquals("localhost", config.redis().host());
        assertFalse(config.security().hasBootstrapAdmin());
    }

    private static void assertInvalid(String key, String value, String expectedMessage) {
        Map<String, String> env = minimalEnv();
        env.put(key, value);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> AppConfig.from(env));
        assertEquals(expectedMessage, error.getMessage());
    }

    private static Map<String, String> minimalEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("DATABASE_USER", "pgmanager");
        env.put("DATABASE_PASSWORD", "db-password");
        env.put("JWT_SECRET", STRONG_SECRET);
        return env;
    }
}
