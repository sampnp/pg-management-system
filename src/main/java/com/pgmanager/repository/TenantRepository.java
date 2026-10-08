package com.pgmanager.repository;

import com.pgmanager.exception.ConflictException;
import com.pgmanager.model.Tenant;
import com.pgmanager.model.TenantStatus;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for the tenants table.
 * Methods with a SqlClient parameter are used inside check-in/check-out transactions:
 * pass the transaction's connection so they run as part of that transaction.
 */
public class TenantRepository {

    private static final String COLUMNS =
            "id, name, phone, email, joining_date, monthly_rent, security_deposit, status, created_at";

    private final Pool pool;

    public TenantRepository(Pool pool) {
        this.pool = pool;
    }

    /** New tenants get the column default status, PENDING. */
    public Future<Tenant> create(String name, String phone, String email, LocalDate joiningDate,
                                 BigDecimal monthlyRent, BigDecimal securityDeposit) {
        return pool.preparedQuery("""
                        INSERT INTO tenants (name, phone, email, joining_date, monthly_rent, security_deposit)
                        VALUES ($1, $2, $3, $4, $5, $6)
                        RETURNING\s""" + COLUMNS)
                .execute(Tuple.of(name, phone, email, joiningDate, monthlyRent, securityDeposit))
                .map(rows -> toTenant(rows.iterator().next()));
    }

    public Future<List<Tenant>> findAll() {
        return pool.query("SELECT " + COLUMNS + " FROM tenants ORDER BY created_at, id")
                .execute()
                .map(rows -> DbUtils.mapAll(rows, TenantRepository::toTenant));
    }

    public Future<Optional<Tenant>> findById(UUID id) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM tenants WHERE id = $1")
                .execute(Tuple.of(id))
                .map(rows -> DbUtils.firstRow(rows).map(TenantRepository::toTenant));
    }

    /**
     * Same as findById, but locks the row until the transaction ends (FOR UPDATE).
     * A second check-in/check-out for the same tenant waits here instead of running at the same time.
     */
    public Future<Optional<Tenant>> findByIdForUpdate(SqlClient client, UUID id) {
        return client.preparedQuery("SELECT " + COLUMNS + " FROM tenants WHERE id = $1 FOR UPDATE")
                .execute(Tuple.of(id))
                .map(rows -> DbUtils.firstRow(rows).map(TenantRepository::toTenant));
    }

    /** Updates personal details only - status is changed by check-in/check-out. Empty if no tenant has this id. */
    public Future<Optional<Tenant>> update(UUID id, String name, String phone, String email, LocalDate joiningDate,
                                           BigDecimal monthlyRent, BigDecimal securityDeposit) {
        return pool.preparedQuery("""
                        UPDATE tenants
                        SET name = $2, phone = $3, email = $4, joining_date = $5, monthly_rent = $6, security_deposit = $7
                        WHERE id = $1
                        RETURNING\s""" + COLUMNS)
                .execute(Tuple.of(id, name, phone, email, joiningDate, monthlyRent, securityDeposit))
                .map(rows -> DbUtils.firstRow(rows).map(TenantRepository::toTenant));
    }

    public Future<Void> updateStatus(SqlClient client, UUID id, TenantStatus status) {
        return client.preparedQuery("UPDATE tenants SET status = $2 WHERE id = $1")
                .execute(Tuple.of(id, status.name()))
                .mapEmpty();
    }

    /** Returns false if no tenant has this id. Fails with 409 if occupancy history references the tenant. */
    public Future<Boolean> delete(UUID id) {
        return pool.preparedQuery("DELETE FROM tenants WHERE id = $1")
                .execute(Tuple.of(id))
                .map(rows -> rows.rowCount() > 0)
                .recover(err -> Future.failedFuture(DbUtils.isForeignKeyViolation(err)
                        ? new ConflictException("Tenant cannot be deleted because they have occupancy history")
                        : err));
    }

    private static Tenant toTenant(Row row) {
        return new Tenant(
                row.getUUID("id"),
                row.getString("name"),
                row.getString("phone"),
                row.getString("email"),
                row.getLocalDate("joining_date"),
                row.getBigDecimal("monthly_rent"),
                row.getBigDecimal("security_deposit"),
                TenantStatus.valueOf(row.getString("status")),
                row.getOffsetDateTime("created_at").toInstant());
    }
}
