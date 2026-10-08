package com.pgmanager.repository;

import com.pgmanager.dto.DashboardSummary;
import com.pgmanager.dto.PropertyDashboard;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.util.Optional;
import java.util.UUID;

/** Aggregate queries for the PG-wide and the per-property dashboard. Read-only. */
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

    /**
     * The same numbers for one property, from its beds (bed -> room -> property). Stays, payments and issues are
     * found through those beds. A payment belongs to the property where the tenant was staying during that rent
     * month (the stay overlaps the month), so the numbers stay correct after the tenant checks out.
     * No row comes back when the property does not exist.
     */
    private static final String PROPERTY_SQL = """
            WITH property_beds AS (
                     SELECT b.id, b.status FROM beds b JOIN rooms r ON r.id = b.room_id WHERE r.property_id = $1),
                 stays AS (
                     SELECT h.tenant_id, h.check_in, h.check_out FROM tenant_bed_history h
                     WHERE h.bed_id IN (SELECT id FROM property_beds)),
                 property_payments AS (
                     SELECT p.amount, p.status FROM payments p
                     WHERE EXISTS (SELECT 1 FROM stays s
                                   WHERE s.tenant_id = p.tenant_id
                                     AND s.check_in < to_date(p.rent_month, 'YYYY-MM') + INTERVAL '1 month'
                                     AND (s.check_out IS NULL OR s.check_out >= to_date(p.rent_month, 'YYYY-MM')))),
                 property_issues AS (
                     SELECT m.status, m.priority FROM maintenance_issues m WHERE m.bed_id IN (SELECT id FROM property_beds))
            SELECT pr.id AS property_id,
                   (SELECT count(*) FROM rooms WHERE property_id = pr.id) AS rooms,
                   b.beds_total, b.beds_available, b.beds_occupied,
                   (SELECT count(*) FROM stays WHERE check_out IS NULL) AS tenants_active,
                   (SELECT count(DISTINCT s.tenant_id) FROM stays s
                    WHERE s.check_out IS NOT NULL
                      AND NOT EXISTS (SELECT 1 FROM stays cur WHERE cur.tenant_id = s.tenant_id AND cur.check_out IS NULL))
                       AS tenants_checked_out,
                   pay.paid_count, pay.pending_count, pay.paid_amount, pay.pending_amount,
                   m.issues_open, m.issues_in_progress, m.issues_resolved, m.issues_closed, m.issues_urgent,
                   now() AS generated_at
            FROM properties pr,
                 (SELECT count(*) AS beds_total,
                         count(*) FILTER (WHERE status = 'AVAILABLE') AS beds_available,
                         count(*) FILTER (WHERE status = 'OCCUPIED') AS beds_occupied
                  FROM property_beds) b,
                 (SELECT count(*) FILTER (WHERE status = 'PAID') AS paid_count,
                         count(*) FILTER (WHERE status = 'PENDING') AS pending_count,
                         COALESCE(sum(amount) FILTER (WHERE status = 'PAID'), 0)::NUMERIC(14, 2) AS paid_amount,
                         COALESCE(sum(amount) FILTER (WHERE status = 'PENDING'), 0)::NUMERIC(14, 2) AS pending_amount
                  FROM property_payments) pay,
                 (SELECT count(*) FILTER (WHERE status = 'OPEN') AS issues_open,
                         count(*) FILTER (WHERE status = 'IN_PROGRESS') AS issues_in_progress,
                         count(*) FILTER (WHERE status = 'RESOLVED') AS issues_resolved,
                         count(*) FILTER (WHERE status = 'CLOSED') AS issues_closed,
                         count(*) FILTER (WHERE priority = 'URGENT' AND status IN ('OPEN', 'IN_PROGRESS')) AS issues_urgent
                  FROM property_issues) m
            WHERE pr.id = $1
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

    /** Empty if the property does not exist. */
    public Future<Optional<PropertyDashboard>> loadPropertySummary(UUID propertyId) {
        return pool.preparedQuery(PROPERTY_SQL)
                .execute(Tuple.of(propertyId))
                .map(rows -> DbUtils.firstRow(rows).map(DashboardRepository::toPropertyDashboard));
    }

    private static PropertyDashboard toPropertyDashboard(Row row) {
        return new PropertyDashboard(
                row.getUUID("property_id"),
                row.getLong("rooms"),
                beds(row),
                new PropertyDashboard.Tenants(row.getLong("tenants_active"), row.getLong("tenants_checked_out")),
                payments(row),
                maintenance(row),
                row.getOffsetDateTime("generated_at").toInstant());
    }

    private static DashboardSummary.Beds beds(Row row) {
        return new DashboardSummary.Beds(row.getLong("beds_total"), row.getLong("beds_available"), row.getLong("beds_occupied"));
    }

    private static DashboardSummary.Payments payments(Row row) {
        return new DashboardSummary.Payments(row.getLong("paid_count"), row.getLong("pending_count"),
                row.getBigDecimal("paid_amount"), row.getBigDecimal("pending_amount"));
    }

    private static DashboardSummary.Maintenance maintenance(Row row) {
        return new DashboardSummary.Maintenance(row.getLong("issues_open"), row.getLong("issues_in_progress"),
                row.getLong("issues_resolved"), row.getLong("issues_closed"), row.getLong("issues_urgent"));
    }

    private static DashboardSummary toSummary(Row row) {
        return new DashboardSummary(
                row.getLong("properties"),
                row.getLong("rooms"),
                beds(row),
                new DashboardSummary.Tenants(
                        row.getLong("tenants_pending"), row.getLong("tenants_active"), row.getLong("tenants_checked_out")),
                payments(row),
                maintenance(row),
                row.getOffsetDateTime("generated_at").toInstant());
    }
}
