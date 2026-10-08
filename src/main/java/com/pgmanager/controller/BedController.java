package com.pgmanager.controller;

import com.pgmanager.dto.BedRequest;
import com.pgmanager.dto.BedStatusRequest;
import com.pgmanager.service.BedService;
import io.vertx.ext.web.RoutingContext;

/** Thin HTTP layer for beds. */
public class BedController {

    private final BedService bedService;

    public BedController(BedService bedService) {
        this.bedService = bedService;
    }

    /** POST /api/rooms/:roomId/beds */
    public void create(RoutingContext ctx) {
        bedService.create(PathParams.uuid(ctx, "roomId"), ctx.body().asPojo(BedRequest.class))
                .onSuccess(bed -> {
                    ctx.response().setStatusCode(201);
                    ctx.json(bed);
                })
                .onFailure(ctx::fail);
    }

    /** GET /api/rooms/:roomId/beds */
    public void listByRoom(RoutingContext ctx) {
        bedService.listByRoom(PathParams.uuid(ctx, "roomId"))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    /** GET /api/beds/:id */
    public void get(RoutingContext ctx) {
        bedService.findById(PathParams.uuid(ctx, "id"))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    /** PUT /api/beds/:id */
    public void update(RoutingContext ctx) {
        bedService.update(PathParams.uuid(ctx, "id"), ctx.body().asPojo(BedRequest.class))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    /** PATCH /api/beds/:id/status */
    public void updateStatus(RoutingContext ctx) {
        bedService.updateStatus(PathParams.uuid(ctx, "id"), ctx.body().asPojo(BedStatusRequest.class))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    /** DELETE /api/beds/:id */
    public void delete(RoutingContext ctx) {
        bedService.delete(PathParams.uuid(ctx, "id"))
                .onSuccess(v -> ctx.response().setStatusCode(204).end())
                .onFailure(ctx::fail);
    }
}
