package com.pgmanager.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of the users table. Never send this to clients directly - use UserResponse.
 * tenantId is set only for TENANT accounts: it links the login to that tenant.
 * active = false means an ADMIN switched the account off. tokenVersion is copied into every JWT and increased
 * when the password changes, which makes all older tokens invalid.
 */
public record User(UUID id, String name, String email, String passwordHash, Role role, Instant createdAt, UUID tenantId,
                   boolean active, int tokenVersion) {

    /** A tenant's account as it is right after creation (active, token version 0). */
    public User(UUID id, String name, String email, String passwordHash, Role role, Instant createdAt, UUID tenantId) {
        this(id, name, email, passwordHash, role, createdAt, tenantId, true, 0);
    }

    /** A staff account (ADMIN or MANAGER), which is not linked to a tenant. */
    public User(UUID id, String name, String email, String passwordHash, Role role, Instant createdAt) {
        this(id, name, email, passwordHash, role, createdAt, null);
    }

    @Override
    public String toString() {
        return "User[id=%s, email=%s, role=%s, active=%s]".formatted(id, email, role, active);
    }
}
