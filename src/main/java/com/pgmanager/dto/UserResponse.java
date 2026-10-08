package com.pgmanager.dto;

import com.pgmanager.model.Role;
import com.pgmanager.model.User;

import java.util.UUID;

/** Safe view of a user: no password hash. tenantId is null for staff accounts. */
public record UserResponse(UUID id, String name, String email, Role role, UUID tenantId) {

    public static UserResponse from(User user) {
        return new UserResponse(user.id(), user.name(), user.email(), user.role(), user.tenantId());
    }
}
