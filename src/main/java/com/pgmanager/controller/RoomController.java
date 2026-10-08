package com.pgmanager.controller;

import com.pgmanager.dto.RoomRequest;
import com.pgmanager.service.RoomService;
import io.vertx.ext.web.RoutingContext;

/** Thin HTTP layer for rooms. */
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    /** POST /api/properties/:propertyId/rooms */
    public void create(RoutingContext ctx) {
        roomService.create(PathParams.uuid(ctx, "propertyId"), ctx.body().asPojo(RoomRequest.class))
                .onSuccess(room -> {
                    ctx.response().setStatusCode(201);
                    ctx.json(room);
                })
                .onFailure(ctx::fail);
    }

    /** GET /api/properties/:propertyId/rooms */
    public void listByProperty(RoutingContext ctx) {
        roomService.listByProperty(PathParams.uuid(ctx, "propertyId"))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    /** GET /api/rooms/:id */
    public void get(RoutingContext ctx) {
        roomService.findById(PathParams.uuid(ctx, "id"))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    /** PUT /api/rooms/:id */
    public void update(RoutingContext ctx) {
        roomService.update(PathParams.uuid(ctx, "id"), ctx.body().asPojo(RoomRequest.class))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    /** DELETE /api/rooms/:id */
    public void delete(RoutingContext ctx) {
        roomService.delete(PathParams.uuid(ctx, "id"))
                .onSuccess(v -> ctx.response().setStatusCode(204).end())
                .onFailure(ctx::fail);
    }
}
