package com.pgmanager.security;

import com.pgmanager.exception.UnauthorizedException;
import com.pgmanager.model.Role;
import com.pgmanager.model.User;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The account check JwtAuthHandler does after the token's signature is verified. */
class JwtAuthHandlerTest {

    private final UUID userId = UUID.randomUUID();
    private final AuthUser claims = new AuthUser(userId, "sambit@example.com", Role.ADMIN, null, 3);

    @Test
    void roleAndTenantComeFromTheDatabaseNotFromTheToken() {
        UUID tenantId = UUID.randomUUID();
        User demoted = user(Role.MANAGER, tenantId, true, 3);

        AuthUser current = JwtAuthHandler.currentAccount(claims, Optional.of(demoted));

        assertEquals(Role.MANAGER, current.role());
        assertEquals(tenantId, current.tenantId());
        assertEquals(userId, current.id());
    }

    @Test
    void deletedAccountIsRejected() {
        UnauthorizedException error = assertThrows(UnauthorizedException.class,
                () -> JwtAuthHandler.currentAccount(claims, Optional.empty()));
        assertEquals("Invalid or expired token", error.getMessage());
    }

    @Test
    void tokenFromBeforeTheLastPasswordChangeIsRejected() {
        UnauthorizedException error = assertThrows(UnauthorizedException.class,
                () -> JwtAuthHandler.currentAccount(claims, Optional.of(user(Role.ADMIN, null, true, 4))));
        assertEquals("Invalid or expired token", error.getMessage());
    }

    @Test
    void switchedOffAccountIsRejected() {
        UnauthorizedException error = assertThrows(UnauthorizedException.class,
                () -> JwtAuthHandler.currentAccount(claims, Optional.of(user(Role.ADMIN, null, false, 3))));
        assertEquals("Account is disabled", error.getMessage());
    }

    private User user(Role role, UUID tenantId, boolean active, int tokenVersion) {
        return new User(userId, "Sambit", "sambit@example.com", "hash", role, Instant.now(), tenantId, active, tokenVersion);
    }
}
