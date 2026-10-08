package com.pgmanager.controller;

import com.pgmanager.service.DashboardService;
import io.vertx.ext.web.RoutingContext;

/** Thin HTTP layer for the staff dashboard. */
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** GET /api/dashboard */
    public void get(RoutingContext ctx) {
        dashboardService.getSummary()
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }
}
