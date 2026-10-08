package com.pgmanager.controller;

import com.pgmanager.dto.CreateUserRequest;
import com.pgmanager.dto.LoginRequest;
import com.pgmanager.dto.LoginResponse;
import com.pgmanager.dto.RegisterRequest;
import com.pgmanager.dto.TenantAccountRequest;
import com.pgmanager.dto.UserResponse;
import com.pgmanager.security.JwtAuthHandler;
import com.pgmanager.service.AuthService;
import io.vertx.ext.web.RoutingContext;

/**
 * HTTP layer only: read the request, call the service, write the response.
 * Failures are passed to ctx.fail(), which hands them to GlobalErrorHandler.
 */
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** POST /api/auth/register */
    public void register(RoutingContext ctx) {
        // asPojo throws DecodeException on malformed JSON; Vert.x routes that to the failure handler (400)
        RegisterRequest request = ctx.body().asPojo(RegisterRequest.class);
        authService.register(request)
                .onSuccess(user -> {
                    ctx.response().setStatusCode(201);
                    ctx.json(UserResponse.from(user));
                })
                .onFailure(ctx::fail);
    }

    /** POST /api/auth/login */
    public void login(RoutingContext ctx) {
        LoginRequest request = ctx.body().asPojo(LoginRequest.class);
        authService.login(request)
                .onSuccess(token -> ctx.json(new LoginResponse(token)))
                .onFailure(ctx::fail);
    }

    /** POST /api/admin/users - an ADMIN creates a staff account (ADMIN or MANAGER). */
    public void createStaffAccount(RoutingContext ctx) {
        authService.createStaffAccount(ctx.body().asPojo(CreateUserRequest.class))
                .onSuccess(user -> {
                    ctx.response().setStatusCode(201);
                    ctx.json(UserResponse.from(user));
                })
                .onFailure(ctx::fail);
    }

    /** POST /api/tenants/:tenantId/account - staff create the login a tenant uses to report maintenance issues. */
    public void createTenantAccount(RoutingContext ctx) {
        authService.createTenantAccount(PathParams.uuid(ctx, "tenantId"), ctx.body().asPojo(TenantAccountRequest.class))
                .onSuccess(user -> {
                    ctx.response().setStatusCode(201);
                    ctx.json(UserResponse.from(user));
                })
                .onFailure(ctx::fail);
    }

    /** GET /api/auth/me - answered from the verified token, no database call needed. */
    public void me(RoutingContext ctx) {
        ctx.json(JwtAuthHandler.currentUser(ctx));
    }
}
