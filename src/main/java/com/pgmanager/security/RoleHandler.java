package com.pgmanager.security;

import com.pgmanager.exception.ForbiddenException;
import com.pgmanager.exception.UnauthorizedException;
import com.pgmanager.model.Role;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

import java.util.Set;

/** Role-based authorization. Must be placed after JwtAuthHandler on a route. */
public final class RoleHandler {

    private RoleHandler() {
    }

    /** Usage: router.get("/x").handler(jwtAuthHandler).handler(RoleHandler.requireRole(Role.ADMIN)).handler(...) */
    public static Handler<RoutingContext> requireRole(Role... allowedRoles) {
        Set<Role> allowed = Set.of(allowedRoles);
        return ctx -> {
            AuthUser user = JwtAuthHandler.currentUser(ctx);
            if (user == null) {
                ctx.fail(new UnauthorizedException("Authentication required"));
            } else if (!allowed.contains(user.role())) {
                ctx.fail(new ForbiddenException("Insufficient permissions"));
            } else {
                ctx.next();
            }
        };
    }
}
