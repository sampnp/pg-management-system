package com.pgmanager.repository;

import com.pgmanager.dto.Page;
import com.pgmanager.dto.PageRequest;
import com.pgmanager.exception.NotFoundException;
import com.pgmanager.model.MaintenanceCategory;
import com.pgmanager.model.MaintenanceIssue;
import com.pgmanager.model.MaintenancePriority;
import com.pgmanager.model.MaintenanceStatus;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for the maintenance_issues table. There is no delete: issues are kept as the tenant's history.
 *
 * The update methods take the status the service checked ("expectedStatus") and only change the row if it
 * still has that status. If two staff members change the same issue at the same time, the second update
 * matches no row and returns empty instead of silently overwriting the first one.
 */
public class MaintenanceRepository {

    /** Room and property come from the issue's bed, so they are joined in rather than stored again. */
    private static final String SELECT = """
            SELECT m.id, m.tenant_id, m.bed_id, b.room_id, r.property_id, m.title, m.description, m.category,
                   m.priority, m.status, m.assigned_to, m.created_at, m.updated_at, m.resolved_at
            FROM %s m
            JOIN beds b ON b.id = m.bed_id
            JOIN rooms r ON r.id = b.room_id
            """;
    private static final String FROM_TABLE = SELECT.formatted("maintenance_issues");
    /** For INSERT/UPDATE ... RETURNING: run the change in a WITH query, then select the result with the joins. */
    private static final String FROM_CHANGED_ROW = SELECT.formatted("changed");
    private static final String NEWEST_FIRST = " ORDER BY m.created_at DESC, m.id DESC";

    private final Pool pool;

    public MaintenanceRepository(Pool pool) {
        this.pool = pool;
    }

    /**
     * Creates an issue at the tenant's current bed. The bed is read from the tenant's open stay in the same
     * statement, so it is always where the tenant really lives. Empty if the tenant is not checked in.
     */
    public Future<Optional<MaintenanceIssue>> create(UUID tenantId, String title, String description,
                                                     MaintenanceCategory category, MaintenancePriority priority) {
        return pool.preparedQuery("""
                        WITH changed AS (
                            INSERT INTO maintenance_issues (tenant_id, bed_id, title, description, category, priority)
                            SELECT h.tenant_id, h.bed_id, $2, $3, $4, $5
                            FROM tenant_bed_history h
                            WHERE h.tenant_id = $1 AND h.check_out IS NULL
                            RETURNING *)
                        """ + FROM_CHANGED_ROW)
                .execute(Tuple.of(tenantId, title, description, category.name(), priority.name()))
                .map(rows -> DbUtils.firstRow(rows).map(MaintenanceRepository::toIssue))
                .recover(err -> Future.failedFuture(DbUtils.isForeignKeyViolation(err)
                        ? new NotFoundException("Tenant not found")
                        : err));
    }

    public Future<Optional<MaintenanceIssue>> findById(UUID id) {
        return pool.preparedQuery(FROM_TABLE + "WHERE m.id = $1")
                .execute(Tuple.of(id))
                .map(rows -> DbUtils.firstRow(rows).map(MaintenanceRepository::toIssue));
    }

    /**
     * Issues matching the given filters, newest first. A null filter is ignored. Only "$1, $2..." placeholders
     * are added to the SQL text; the filter values are always sent as parameters.
     */
    public Future<List<MaintenanceIssue>> find(UUID tenantId, MaintenanceStatus status,
                                               MaintenancePriority priority, MaintenanceCategory category) {
        Filter filter = filter(tenantId, status, priority, category);
        return pool.preparedQuery(FROM_TABLE + filter.where() + NEWEST_FIRST)
                .execute(filter.params())
                .map(rows -> DbUtils.mapAll(rows, MaintenanceRepository::toIssue));
    }

    /** Same filters as find(), one page at a time. The count needs no joins: every filter is on the issue itself. */
    public Future<Page<MaintenanceIssue>> findPage(UUID tenantId, MaintenanceStatus status, MaintenancePriority priority,
                                                   MaintenanceCategory category, PageRequest request) {
        Filter filter = filter(tenantId, status, priority, category);
        return DbUtils.page(pool, "SELECT count(*) FROM maintenance_issues m " + filter.where(),
                FROM_TABLE + filter.where() + NEWEST_FIRST, filter.params(), request, MaintenanceRepository::toIssue);
    }

    /** A WHERE clause made only of "$1, $2..." placeholders, and the values that go with them. */
    private record Filter(String where, Tuple params) {
    }

    private static Filter filter(UUID tenantId, MaintenanceStatus status, MaintenancePriority priority,
                                 MaintenanceCategory category) {
        List<String> conditions = new ArrayList<>();
        Tuple params = Tuple.tuple();
        if (tenantId != null) {
            params.addValue(tenantId);
            conditions.add("m.tenant_id = $" + params.size());
        }
        if (status != null) {
            params.addValue(status.name());
            conditions.add("m.status = $" + params.size());
        }
        if (priority != null) {
            params.addValue(priority.name());
            conditions.add("m.priority = $" + params.size());
        }
        if (category != null) {
            params.addValue(category.name());
            conditions.add("m.category = $" + params.size());
        }
        return new Filter(conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions), params);
    }

    /** Edits the reported details. Empty if the issue no longer has expectedStatus. */
    public Future<Optional<MaintenanceIssue>> update(UUID id, String title, String description, MaintenanceCategory category,
                                                     MaintenancePriority priority, MaintenanceStatus expectedStatus) {
        return pool.preparedQuery("""
                        WITH changed AS (
                            UPDATE maintenance_issues
                            SET title = $2, description = $3, category = $4, priority = $5, updated_at = now()
                            WHERE id = $1 AND status = $6
                            RETURNING *)
                        """ + FROM_CHANGED_ROW)
                .execute(Tuple.of(id, title, description, category.name(), priority.name(), expectedStatus.name()))
                .map(rows -> DbUtils.firstRow(rows).map(MaintenanceRepository::toIssue));
    }

    /** Sets the staff user handling the issue. Empty if the issue no longer has expectedStatus. */
    public Future<Optional<MaintenanceIssue>> assign(UUID id, UUID assignedTo, MaintenanceStatus expectedStatus) {
        return pool.preparedQuery("""
                        WITH changed AS (
                            UPDATE maintenance_issues
                            SET assigned_to = $2, updated_at = now()
                            WHERE id = $1 AND status = $3
                            RETURNING *)
                        """ + FROM_CHANGED_ROW)
                .execute(Tuple.of(id, assignedTo, expectedStatus.name()))
                .map(rows -> DbUtils.firstRow(rows).map(MaintenanceRepository::toIssue));
    }

    /**
     * Moves the issue to a new status. resolved_at uses the database clock: it is set when the issue is
     * resolved, cleared when it is reopened, and otherwise kept. Empty if the issue no longer has expectedStatus.
     */
    public Future<Optional<MaintenanceIssue>> changeStatus(UUID id, MaintenanceStatus newStatus, MaintenanceStatus expectedStatus) {
        // Chosen from the enum, never from user input, so it is safe to put in the SQL text
        String resolvedAt = switch (newStatus) {
            case RESOLVED -> "now()";
            case OPEN -> "NULL";
            case IN_PROGRESS, CLOSED -> "resolved_at";
        };
        String sql = """
                WITH changed AS (
                    UPDATE maintenance_issues
                    SET status = $2, resolved_at = %s, updated_at = now()
                    WHERE id = $1 AND status = $3
                    RETURNING *)
                """.formatted(resolvedAt) + FROM_CHANGED_ROW;
        return pool.preparedQuery(sql)
                .execute(Tuple.of(id, newStatus.name(), expectedStatus.name()))
                .map(rows -> DbUtils.firstRow(rows).map(MaintenanceRepository::toIssue));
    }

    private static MaintenanceIssue toIssue(Row row) {
        OffsetDateTime resolvedAt = row.getOffsetDateTime("resolved_at");
        return new MaintenanceIssue(
                row.getUUID("id"),
                row.getUUID("tenant_id"),
                row.getUUID("bed_id"),
                row.getUUID("room_id"),
                row.getUUID("property_id"),
                row.getString("title"),
                row.getString("description"),
                MaintenanceCategory.valueOf(row.getString("category")),
                MaintenancePriority.valueOf(row.getString("priority")),
                MaintenanceStatus.valueOf(row.getString("status")),
                row.getUUID("assigned_to"),
                row.getOffsetDateTime("created_at").toInstant(),
                row.getOffsetDateTime("updated_at").toInstant(),
                resolvedAt == null ? null : resolvedAt.toInstant());
    }
}
