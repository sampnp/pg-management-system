package com.pgmanager.service;

import com.pgmanager.dto.DashboardSummary;
import com.pgmanager.repository.DashboardRepository;
import io.vertx.core.Future;

/** PG-wide statistics for ADMIN and MANAGER users (the route checks the role). */
public class DashboardService {

    private final DashboardRepository dashboardRepository;

    public DashboardService(DashboardRepository dashboardRepository) {
        this.dashboardRepository = dashboardRepository;
    }

    public Future<DashboardSummary> getSummary() {
        return dashboardRepository.loadSummary();
    }
}
