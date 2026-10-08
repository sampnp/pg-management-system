package com.pgmanager.controller;

import com.pgmanager.dto.CheckInRequest;
import com.pgmanager.service.OccupancyService;
import io.vertx.ext.web.RoutingContext;

/** Thin HTTP layer for check-in, check-out and occupancy lookups. */
public class OccupancyController {

    private final OccupancyService occupancyService;

    public OccupancyController(OccupancyService occupancyService) {
        this.occupancyService = occupancyService;
    }

    /** POST /api/tenants/:tenantId/check-in  body: {"bedId": "..."} */
    public void checkIn(RoutingContext ctx) {
        occupancyService.checkIn(PathParams.uuid(ctx, "tenantId"), ctx.body().asPojo(CheckInRequest.class))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    /** POST /api/tenants/:tenantId/check-out  (no body) */
    public void checkOut(RoutingContext ctx) {
        occupancyService.checkOut(PathParams.uuid(ctx, "tenantId"))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }
}
