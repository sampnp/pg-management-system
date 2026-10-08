package com.pgmanager.security;

import com.pgmanager.exception.UnauthorizedException;
import com.pgmanager.model.User;
import com.pgmanager.repository.UserRepository;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Middleware for protected routes. Expects "Authorization: Bearer <token>".
 *
 * 1. The JWT's signature and expiry are checked (no database needed).
 * 2. The account is looked up by id (one primary-key query). The request is refused if the account was
 *    deleted, switched off, or the token is older than the last password change (token version).
 *    The role and tenant then come from the database, so a role change applies to the very next request.
 *
 * On success the AuthUser is stored in the RoutingContext and ctx.next() is called;
 * otherwise the request fails with 401 and never reaches the controller.
 */
public class JwtAuthHandler implements Handler<RoutingContext> {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthHandler.class);
    private static final String AUTH_USER_KEY = "authUser";
    private static final String BEARER_PREFIX = "Bearer ";
    static final String INVALID_TOKEN = "Invalid or expired token";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthHandler(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
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
                // The real reason (expired, bad signature...) is logged, not sent to the client
                .recover(err -> {
                    log.debug("Rejected JWT: {}", err.getMessage());
                    return Future.failedFuture(new UnauthorizedException(INVALID_TOKEN));
                })
                // A database error here is not the caller's fault, so it stays a 500 (not a 401)
                .compose(claims -> userRepository.findById(claims.id())
                        .map(account -> currentAccount(claims, account)))
                .onSuccess(user -> {
                    ctx.put(AUTH_USER_KEY, user);
                    ctx.next();
                })
                .onFailure(ctx::fail);
    }

    /** The caller as stored now, or a 401 if the token no longer belongs to a usable account. */
    static AuthUser currentAccount(AuthUser claims, Optional<User> account) {
        if (account.isEmpty() || account.get().tokenVersion() != claims.tokenVersion()) {
            // Deleted account, or a token from before the last password change
            throw new UnauthorizedException(INVALID_TOKEN);
        }
        User user = account.get();
        if (!user.active()) {
            throw new UnauthorizedException("Account is disabled");
        }
        return new AuthUser(user.id(), user.email(), user.role(), user.tenantId(), user.tokenVersion());
    }

    /** The user set by this handler, or null if the route is not protected. */
    public static AuthUser currentUser(RoutingContext ctx) {
        return ctx.get(AUTH_USER_KEY);
    }
}
