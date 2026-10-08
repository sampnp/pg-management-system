package com.pgmanager.controller;

import com.pgmanager.dto.PropertyRequest;
import com.pgmanager.service.PropertyService;
import io.vertx.ext.web.RoutingContext;

/** Thin HTTP layer for /api/properties. Failures go to GlobalErrorHandler via ctx.fail(). */
public class PropertyController {

    private final PropertyService propertyService;

    public PropertyController(PropertyService propertyService) {
        this.propertyService = propertyService;
    }

    /** POST /api/properties */
    public void create(RoutingContext ctx) {
        propertyService.create(ctx.body().asPojo(PropertyRequest.class))
                .onSuccess(property -> {
                    ctx.response().setStatusCode(201);
                    ctx.json(property);
                })
                .onFailure(ctx::fail);
    }

    /** GET /api/properties */
    public void list(RoutingContext ctx) {
        propertyService.findAll()
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    /** GET /api/properties/:id */
    public void get(RoutingContext ctx) {
        propertyService.findById(PathParams.uuid(ctx, "id"))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    /** PUT /api/properties/:id */
    public void update(RoutingContext ctx) {
        propertyService.update(PathParams.uuid(ctx, "id"), ctx.body().asPojo(PropertyRequest.class))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    /** DELETE /api/properties/:id - 204 No Content on success */
    public void delete(RoutingContext ctx) {
        propertyService.delete(PathParams.uuid(ctx, "id"))
                .onSuccess(v -> ctx.response().setStatusCode(204).end())
                .onFailure(ctx::fail);
    }
}
