package com.pgmanager.controller;

import com.pgmanager.dto.AssignRequest;
import com.pgmanager.dto.MaintenanceRequest;
import com.pgmanager.dto.MaintenanceStatusRequest;
import com.pgmanager.security.JwtAuthHandler;
import com.pgmanager.service.MaintenanceService;
import io.vertx.ext.web.RoutingContext;

/** Thin HTTP layer for maintenance issues. There is no DELETE endpoint: issues are kept as the tenant's history. */
public class MaintenanceController {

    private final MaintenanceService maintenanceService;

    public MaintenanceController(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    /** POST /api/maintenance */
    public void create(RoutingContext ctx) {
        maintenanceService.create(JwtAuthHandler.currentUser(ctx), ctx.body().asPojo(MaintenanceRequest.class))
                .onSuccess(issue -> {
                    ctx.response().setStatusCode(201);
                    ctx.json(issue);
                })
                .onFailure(ctx::fail);
    }

    /** GET /api/maintenance?tenantId=...&status=OPEN&priority=HIGH&category=PLUMBING (all filters optional) */
    public void list(RoutingContext ctx) {
        maintenanceService.list(
                        ctx.queryParams().get("tenantId"),
                        ctx.queryParams().get("status"),
                        ctx.queryParams().get("priority"),
                        ctx.queryParams().get("category"))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    /** GET /api/maintenance/:id */
    public void get(RoutingContext ctx) {
        maintenanceService.findById(JwtAuthHandler.currentUser(ctx), PathParams.uuid(ctx, "id"))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    /** PUT /api/maintenance/:id */
    public void update(RoutingContext ctx) {
        maintenanceService.update(JwtAuthHandler.currentUser(ctx), PathParams.uuid(ctx, "id"),
                        ctx.body().asPojo(MaintenanceRequest.class))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    /** PATCH /api/maintenance/:id/assign  body: {"assignedTo": "<user id>"} */
    public void assign(RoutingContext ctx) {
        maintenanceService.assign(PathParams.uuid(ctx, "id"), ctx.body().asPojo(AssignRequest.class))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    /** PATCH /api/maintenance/:id/status  body: {"status": "IN_PROGRESS"} */
    public void changeStatus(RoutingContext ctx) {
        maintenanceService.changeStatus(PathParams.uuid(ctx, "id"), ctx.body().asPojo(MaintenanceStatusRequest.class))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    /** GET /api/tenants/:tenantId/maintenance */
    public void tenantHistory(RoutingContext ctx) {
        maintenanceService.tenantHistory(JwtAuthHandler.currentUser(ctx), PathParams.uuid(ctx, "tenantId"))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }
}
