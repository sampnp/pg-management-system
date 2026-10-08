package com.pgmanager.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    // Low cost keeps the tests fast; production uses DEFAULT_COST
    private final PasswordHasher hasher = new PasswordHasher(4);

    @Test
    void hashIsNotThePlainPasswordAndMatchesIt() {
        String hash = hasher.hash("password123");

        assertNotEquals("password123", hash);
        assertTrue(hash.startsWith("$2"), "expected a BCrypt hash");
        assertTrue(hasher.matches("password123", hash));
    }

    @Test
    void wrongPasswordDoesNotMatch() {
        String hash = hasher.hash("password123");

        assertFalse(hasher.matches("password124", hash));
    }

    @Test
    void samePasswordGivesDifferentHashesBecauseOfRandomSalt() {
        assertNotEquals(hasher.hash("password123"), hasher.hash("password123"));
    }

    @Test
    void passwordLongerThan72BytesNeverMatches() {
        String hash = hasher.hash("password123");

        assertFalse(hasher.matches("x".repeat(73), hash));
    }
}
