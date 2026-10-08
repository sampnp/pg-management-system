package com.pgmanager.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response of GET /api/dashboard: counts across the whole PG, computed by one SQL query.
 * generatedAt is when the numbers were calculated (they may come from the cache, so it can be a little in the past).
 */
public record DashboardSummary(
        long properties,
        long rooms,
        Beds beds,
        Tenants tenants,
        Payments payments,
        Maintenance maintenance,
        Instant generatedAt) {

    public record Beds(long total, long available, long occupied) {
    }

    public record Tenants(long pending, long active, long checkedOut) {
    }

    /** Amounts are totals over all payments ever recorded, not just the current month. */
    public record Payments(long paidCount, long pendingCount, BigDecimal paidAmount, BigDecimal pendingAmount) {
    }

    /** urgent = URGENT issues that still need work (OPEN or IN_PROGRESS). */
    public record Maintenance(long open, long inProgress, long resolved, long closed, long urgent) {
    }
}
