package com.pgmanager.repository;

import com.pgmanager.dto.DashboardSummary;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;

/** Aggregate queries for the dashboard. Read-only. */
public class DashboardRepository {

    /**
     * Every statistic in one statement: each sub-select scans one table once and counts with FILTER
     * (like COUNT(*) WHERE ..., but several counts per scan). One round trip instead of a query per number,
     * and because it is a single statement, all numbers come from the same consistent snapshot.
     */
    private static final String SUMMARY_SQL = """
            SELECT p.properties, r.rooms,
                   b.beds_total, b.beds_available, b.beds_occupied,
                   t.tenants_pending, t.tenants_active, t.tenants_checked_out,
                   pay.paid_count, pay.pending_count, pay.paid_amount, pay.pending_amount,
                   m.issues_open, m.issues_in_progress, m.issues_resolved, m.issues_closed, m.issues_urgent,
                   now() AS generated_at
            FROM (SELECT count(*) AS properties FROM properties) p,
                 (SELECT count(*) AS rooms FROM rooms) r,
                 (SELECT count(*) AS beds_total,
                         count(*) FILTER (WHERE status = 'AVAILABLE') AS beds_available,
                         count(*) FILTER (WHERE status = 'OCCUPIED') AS beds_occupied
                  FROM beds) b,
                 (SELECT count(*) FILTER (WHERE status = 'PENDING') AS tenants_pending,
                         count(*) FILTER (WHERE status = 'ACTIVE') AS tenants_active,
                         count(*) FILTER (WHERE status = 'CHECKED_OUT') AS tenants_checked_out
                  FROM tenants) t,
                 (SELECT count(*) FILTER (WHERE status = 'PAID') AS paid_count,
                         count(*) FILTER (WHERE status = 'PENDING') AS pending_count,
                         COALESCE(sum(amount) FILTER (WHERE status = 'PAID'), 0)::NUMERIC(14, 2) AS paid_amount,
                         COALESCE(sum(amount) FILTER (WHERE status = 'PENDING'), 0)::NUMERIC(14, 2) AS pending_amount
                  FROM payments) pay,
                 (SELECT count(*) FILTER (WHERE status = 'OPEN') AS issues_open,
                         count(*) FILTER (WHERE status = 'IN_PROGRESS') AS issues_in_progress,
                         count(*) FILTER (WHERE status = 'RESOLVED') AS issues_resolved,
                         count(*) FILTER (WHERE status = 'CLOSED') AS issues_closed,
                         count(*) FILTER (WHERE priority = 'URGENT' AND status IN ('OPEN', 'IN_PROGRESS')) AS issues_urgent
                  FROM maintenance_issues) m
            """;

    private final Pool pool;

    public DashboardRepository(Pool pool) {
        this.pool = pool;
    }

    public Future<DashboardSummary> loadSummary() {
        return pool.query(SUMMARY_SQL)
                .execute()
                .map(rows -> toSummary(rows.iterator().next()));
    }

    private static DashboardSummary toSummary(Row row) {
        return new DashboardSummary(
                row.getLong("properties"),
                row.getLong("rooms"),
                new DashboardSummary.Beds(
                        row.getLong("beds_total"), row.getLong("beds_available"), row.getLong("beds_occupied")),
                new DashboardSummary.Tenants(
                        row.getLong("tenants_pending"), row.getLong("tenants_active"), row.getLong("tenants_checked_out")),
                new DashboardSummary.Payments(
                        row.getLong("paid_count"), row.getLong("pending_count"),
                        row.getBigDecimal("paid_amount"), row.getBigDecimal("pending_amount")),
                new DashboardSummary.Maintenance(
                        row.getLong("issues_open"), row.getLong("issues_in_progress"), row.getLong("issues_resolved"),
                        row.getLong("issues_closed"), row.getLong("issues_urgent")),
                row.getOffsetDateTime("generated_at").toInstant());
    }
}
