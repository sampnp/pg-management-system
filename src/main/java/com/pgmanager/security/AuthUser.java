package com.pgmanager.security;

import com.pgmanager.model.Role;

import java.util.UUID;

/**
 * The authenticated caller, built from a verified JWT. Also used as the GET /api/auth/me response.
 * tenantId is set only for TENANT users: it says which tenant's data they may access.
 */
public record AuthUser(UUID id, String email, Role role, UUID tenantId) {

    /** A staff user (ADMIN or MANAGER). */
    public AuthUser(UUID id, String email, Role role) {
        this(id, email, role, null);
    }
}
