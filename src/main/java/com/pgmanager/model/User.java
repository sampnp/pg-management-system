package com.pgmanager.model;

import java.time.Instant;
import java.util.UUID;

/** A row of the users table. Never send this to clients directly - use UserResponse. */
public record User(UUID id, String name, String email, String passwordHash, Role role, Instant createdAt) {

    @Override
    public String toString() {
        return "User[id=%s, email=%s, role=%s]".formatted(id, email, role);
    }
}
