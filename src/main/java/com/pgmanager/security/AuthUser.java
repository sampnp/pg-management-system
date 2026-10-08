package com.pgmanager.security;

import com.pgmanager.model.Role;

import java.util.UUID;

/** The authenticated caller, built from a verified JWT. Also used as the GET /api/auth/me response. */
public record AuthUser(UUID id, String email, Role role) {
}
