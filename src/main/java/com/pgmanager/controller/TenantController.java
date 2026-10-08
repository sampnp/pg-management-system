package com.pgmanager.controller;

import com.pgmanager.dto.TenantRequest;
import com.pgmanager.service.TenantService;
import io.vertx.ext.web.RoutingContext;

/** Thin HTTP layer for /api/tenants. */
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    /** POST /api/tenants */
    public void create(RoutingContext ctx) {
        tenantService.create(ctx.body().asPojo(TenantRequest.class))
                .onSuccess(tenant -> {
                    ctx.response().setStatusCode(201);
                    ctx.json(tenant);
                })
                .onFailure(ctx::fail);
    }

    /** GET /api/tenants?page=0&size=20 */
    public void list(RoutingContext ctx) {
        tenantService.list(ctx.queryParams().get("page"), ctx.queryParams().get("size"))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    /** GET /api/tenants/:id */
    public void get(RoutingContext ctx) {
        tenantService.findById(PathParams.uuid(ctx, "id"))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    /** PUT /api/tenants/:id */
    public void update(RoutingContext ctx) {
        tenantService.update(PathParams.uuid(ctx, "id"), ctx.body().asPojo(TenantRequest.class))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    /** DELETE /api/tenants/:id */
    public void delete(RoutingContext ctx) {
        tenantService.delete(PathParams.uuid(ctx, "id"))
                .onSuccess(v -> ctx.response().setStatusCode(204).end())
                .onFailure(ctx::fail);
    }
}
