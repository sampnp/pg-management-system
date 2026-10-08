package com.pgmanager.security;

import com.pgmanager.exception.ForbiddenException;
import com.pgmanager.exception.UnauthorizedException;
import com.pgmanager.model.Role;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Both existing roles may manage properties, so a 403 cannot happen there over HTTP today.
 * These tests prove RoleHandler itself blocks any role that is not in the allowed set.
 */
class RoleHandlerTest {

    @Test
    void allowedRoleContinuesToNextHandler() {
        RoutingContext ctx = contextWithUser(Role.MANAGER);

        RoleHandler.requireRole(Role.ADMIN, Role.MANAGER).handle(ctx);

        verify(ctx).next();
        verify(ctx, never()).fail(any(Throwable.class));
    }

    @Test
    void roleOutsideAllowedSetFailsWith403() {
        RoutingContext ctx = contextWithUser(Role.MANAGER);

        RoleHandler.requireRole(Role.ADMIN).handle(ctx);

        verify(ctx).fail(isA(ForbiddenException.class));
        verify(ctx, never()).next();
    }

    @Test
    void missingUserFailsWith401() {
        RoutingContext ctx = contextWithUser(null);

        RoleHandler.requireRole(Role.ADMIN, Role.MANAGER).handle(ctx);

        verify(ctx).fail(isA(UnauthorizedException.class));
        verify(ctx, never()).next();
    }

    private static RoutingContext contextWithUser(Role role) {
        RoutingContext ctx = mock(RoutingContext.class);
        AuthUser user = role == null ? null : new AuthUser(UUID.randomUUID(), "user@example.com", role);
        // JwtAuthHandler stores the authenticated user in the context; simulate that here
        when(ctx.<AuthUser>get(anyString())).thenReturn(user);
        return ctx;
    }
}
