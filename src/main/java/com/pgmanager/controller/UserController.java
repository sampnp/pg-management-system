package com.pgmanager.controller;

import com.pgmanager.dto.AccountStatusRequest;
import com.pgmanager.dto.RoleRequest;
import com.pgmanager.dto.UserResponse;
import com.pgmanager.security.JwtAuthHandler;
import com.pgmanager.service.UserService;
import io.vertx.ext.web.RoutingContext;

/** Admin-only user endpoints. */
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** PATCH /api/admin/users/:id/role  body: {"role": "ADMIN"} */
    public void changeRole(RoutingContext ctx) {
        userService.changeRole(JwtAuthHandler.currentUser(ctx), PathParams.uuid(ctx, "id"),
                        ctx.body().asPojo(RoleRequest.class))
                .onSuccess(user -> ctx.json(UserResponse.from(user)))
                .onFailure(ctx::fail);
    }

    /** PATCH /api/admin/users/:id/active  body: {"active": false} */
    public void setActive(RoutingContext ctx) {
        userService.setActive(JwtAuthHandler.currentUser(ctx), PathParams.uuid(ctx, "id"),
                        ctx.body().asPojo(AccountStatusRequest.class))
                .onSuccess(user -> ctx.json(UserResponse.from(user)))
                .onFailure(ctx::fail);
    }
}
