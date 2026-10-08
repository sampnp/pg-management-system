package com.pgmanager.service;

import com.pgmanager.dto.RoleRequest;
import com.pgmanager.exception.BadRequestException;
import com.pgmanager.exception.ForbiddenException;
import com.pgmanager.exception.NotFoundException;
import com.pgmanager.model.Role;
import com.pgmanager.model.User;
import com.pgmanager.repository.UserRepository;
import com.pgmanager.security.AuthUser;
import io.vertx.core.Future;

import java.util.Locale;
import java.util.UUID;

/**
 * Admin-only user management: changing a user's role. This is the only way to get the ADMIN role,
 * because public registration always creates a MANAGER.
 */
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Gives a user the ADMIN or MANAGER role. The route already requires ADMIN; the role is checked
     * again here because this is the one operation that hands out admin rights.
     * The user gets a token with the new role the next time they log in.
     */
    public Future<User> changeRole(AuthUser caller, UUID userId, RoleRequest request) {
        return Future.succeededFuture(request)
                .map(r -> {
                    if (caller == null || caller.role() != Role.ADMIN) {
                        throw new ForbiddenException("Insufficient permissions");
                    }
                    Role role = parseRole(Validation.requireBody(r).role());
                    // Stops an admin from accidentally removing their own access (and leaving no admin at all)
                    if (caller.id().equals(userId)) {
                        throw new BadRequestException("You cannot change your own role");
                    }
                    return role;
                })
                .compose(role -> userRepository.updateRole(userId, role))
                .map(user -> user.orElseThrow(() -> new NotFoundException("User not found")));
    }

    private static Role parseRole(String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("role is required");
        }
        try {
            return Role.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("role must be ADMIN or MANAGER");
        }
    }
}
