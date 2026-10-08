package com.pgmanager.repository;

import com.pgmanager.exception.ConflictException;
import com.pgmanager.model.Occupancy;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for the tenant_bed_history table (one row per stay; check_out IS NULL = current stay).
 * Every method takes a SqlClient because most calls happen inside a transaction:
 * pass the transaction's connection there, or the Pool for normal reads.
 * Rows are only ever inserted or closed (check_out set) - never deleted - so history is preserved.
 */
public class TenantBedHistoryRepository {

    // Joins the bed's room and property so callers get the full location in one query
    private static final String SELECT_OCCUPANCY = """
            SELECT h.id, h.tenant_id, h.bed_id, b.room_id, r.property_id, h.check_in, h.check_out
            FROM tenant_bed_history h
            JOIN beds b ON b.id = h.bed_id
            JOIN rooms r ON r.id = b.room_id
            """;

    public Future<Optional<Occupancy>> findCurrentByTenantId(SqlClient client, UUID tenantId) {
        return client.preparedQuery(SELECT_OCCUPANCY + "WHERE h.tenant_id = $1 AND h.check_out IS NULL")
                .execute(Tuple.of(tenantId))
                .map(rows -> DbUtils.firstRow(rows).map(TenantBedHistoryRepository::toOccupancy));
    }

    public Future<Optional<Occupancy>> findById(SqlClient client, UUID id) {
        return client.preparedQuery(SELECT_OCCUPANCY + "WHERE h.id = $1")
                .execute(Tuple.of(id))
                .map(rows -> DbUtils.firstRow(rows).map(TenantBedHistoryRepository::toOccupancy));
    }

    /** Starts a new stay. check_in is set by the database clock (now()), never by the client. */
    public Future<UUID> insert(SqlClient client, UUID tenantId, UUID bedId) {
        return client.preparedQuery("INSERT INTO tenant_bed_history (tenant_id, bed_id) VALUES ($1, $2) RETURNING id")
                .execute(Tuple.of(tenantId, bedId))
                .map(rows -> rows.iterator().next().getUUID("id"))
                // Last line of defence: the partial unique indexes allow only one open stay per bed and per tenant
                .recover(err -> Future.failedFuture(DbUtils.isUniqueViolation(err)
                        ? new ConflictException("Bed or tenant already has an active check-in")
                        : err));
    }

    /** Ends a stay. The "check_out IS NULL" condition means a finished stay is never changed again. */
    public Future<Void> close(SqlClient client, UUID id) {
        return client.preparedQuery("UPDATE tenant_bed_history SET check_out = now() WHERE id = $1 AND check_out IS NULL")
                .execute(Tuple.of(id))
                .mapEmpty();
    }

    private static Occupancy toOccupancy(Row row) {
        OffsetDateTime checkOut = row.getOffsetDateTime("check_out");
        return new Occupancy(
                row.getUUID("id"),
                row.getUUID("tenant_id"),
                row.getUUID("bed_id"),
                row.getUUID("room_id"),
                row.getUUID("property_id"),
                row.getOffsetDateTime("check_in").toInstant(),
                checkOut == null ? null : checkOut.toInstant());
    }
}
