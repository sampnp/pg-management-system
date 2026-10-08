package com.pgmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Body of POST /api/admin/users: an ADMIN creates a staff account. role must be ADMIN or MANAGER. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateUserRequest(String name, String email, String password, String role) {

    @Override
    public String toString() {
        return "CreateUserRequest[name=%s, email=%s, role=%s]".formatted(name, email, role);
    }
}
