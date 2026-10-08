package com.pgmanager.security;

import com.pgmanager.exception.UnauthorizedException;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Middleware for protected routes. Expects "Authorization: Bearer <token>".
 * On success it stores the AuthUser in the RoutingContext and calls ctx.next();
 * otherwise the request fails with 401 and never reaches the controller.
 */
public class JwtAuthHandler implements Handler<RoutingContext> {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthHandler.class);
    private static final String AUTH_USER_KEY = "authUser";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthHandler(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public void handle(RoutingContext ctx) {
        String header = ctx.request().getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            ctx.fail(new UnauthorizedException("Missing or invalid Authorization header"));
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        jwtService.verify(token)
                .onSuccess(user -> {
                    ctx.put(AUTH_USER_KEY, user);
                    ctx.next();
                })
                .onFailure(err -> {
                    // The real reason (expired, bad signature...) is logged, not sent to the client
                    log.debug("Rejected JWT: {}", err.getMessage());
                    ctx.fail(new UnauthorizedException("Invalid or expired token"));
                });
    }

    /** The user set by this handler, or null if the route is not protected. */
    public static AuthUser currentUser(RoutingContext ctx) {
        return ctx.get(AUTH_USER_KEY);
    }
}
