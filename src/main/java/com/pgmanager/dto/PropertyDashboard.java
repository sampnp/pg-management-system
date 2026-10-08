package com.pgmanager.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response of GET /api/properties/:propertyId/dashboard: the dashboard numbers for one property only.
 * Beds, payments and maintenance reuse the shapes of the PG-wide dashboard.
 *
 * A payment counts for this property when the tenant was staying here during that rent month.
 * Tenants who never checked in (PENDING) belong to no property, so they are not counted here.
 */
public record PropertyDashboard(
        UUID propertyId,
        long rooms,
        DashboardSummary.Beds beds,
        Tenants tenants,
        DashboardSummary.Payments payments,
        DashboardSummary.Maintenance maintenance,
        Instant generatedAt) {

    /** active: checked in at this property now. checkedOut: stayed here before and is not living here now. */
    public record Tenants(long active, long checkedOut) {
    }
}
