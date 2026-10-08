package com.pgmanager.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of the users table. Never send this to clients directly - use UserResponse.
 * tenantId is set only for TENANT accounts: it links the login to that tenant.
 */
public record User(UUID id, String name, String email, String passwordHash, Role role, Instant createdAt, UUID tenantId) {

    /** A staff account (ADMIN or MANAGER), which is not linked to a tenant. */
    public User(UUID id, String name, String email, String passwordHash, Role role, Instant createdAt) {
        this(id, name, email, passwordHash, role, createdAt, null);
    }

    @Override
    public String toString() {
        return "User[id=%s, email=%s, role=%s]".formatted(id, email, role);
    }
}
