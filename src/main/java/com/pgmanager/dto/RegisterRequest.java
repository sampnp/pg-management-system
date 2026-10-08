package com.pgmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Public sign-up. There is deliberately no role field: every account created here is a MANAGER,
 * and a "role" sent by the client is simply ignored (ignoreUnknown). Only an ADMIN can give
 * another user the ADMIN role, through PATCH /api/admin/users/:id/role.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegisterRequest(String name, String email, String password) {

    @Override
    public String toString() {
        return "RegisterRequest[name=%s, email=%s]".formatted(name, email);
    }
}
