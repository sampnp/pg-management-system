package com.pgmanager.security;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.pgmanager.model.Role;

import java.util.UUID;

/**
 * The authenticated caller. Also used as the GET /api/auth/me response.
 * tenantId is set only for TENANT users: it says which tenant's data they may access.
 * tokenVersion is the "ver" claim of the token; it is internal and not part of the JSON.
 */
public record AuthUser(UUID id, String email, Role role, UUID tenantId, @JsonIgnore int tokenVersion) {

    public AuthUser(UUID id, String email, Role role, UUID tenantId) {
        this(id, email, role, tenantId, 0);
    }

    /** A staff user (ADMIN or MANAGER). */
    public AuthUser(UUID id, String email, Role role) {
        this(id, email, role, null);
    }
}
