package com.pgmanager.repository;

import com.pgmanager.exception.ConflictException;
import com.pgmanager.exception.NotFoundException;
import com.pgmanager.model.Payment;
import com.pgmanager.model.PaymentMethod;
import com.pgmanager.model.PaymentStatus;
import com.pgmanager.dto.Page;
import com.pgmanager.dto.PageRequest;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL for the payments table. There is no delete: payment records are financial history and are kept. */
public class PaymentRepository {

    private static final String COLUMNS =
            "id, tenant_id, amount, rent_month, payment_date, payment_method, status, receipt_id, created_at, updated_at";
    /** id last, so payments created in the same instant still have a fixed order (stable pages) */
    private static final String NEWEST_FIRST = " ORDER BY rent_month DESC, created_at DESC, id DESC";

    private final Pool pool;

    public PaymentRepository(Pool pool) {
        this.pool = pool;
    }

    public Future<Payment> create(UUID tenantId, BigDecimal amount, YearMonth rentMonth, LocalDate paymentDate,
                                  PaymentMethod paymentMethod, PaymentStatus status, String receiptId) {
        return pool.preparedQuery("""
                        INSERT INTO payments (tenant_id, amount, rent_month, payment_date, payment_method, status, receipt_id)
                        VALUES ($1, $2, $3, $4, $5, $6, $7)
                        RETURNING\s""" + COLUMNS)
                .execute(Tuple.of(tenantId, amount, rentMonth.toString(), paymentDate, nameOrNull(paymentMethod),
                        status.name(), receiptId))
                .map(rows -> toPayment(rows.iterator().next()))
                .recover(err -> Future.failedFuture(translateWriteError(err)));
    }

    public Future<Optional<Payment>> findById(UUID id) {
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM payments WHERE id = $1")
                .execute(Tuple.of(id))
                .map(rows -> DbUtils.firstRow(rows).map(PaymentRepository::toPayment));
    }

    /**
     * Payments matching the given filters, newest rent month first. A null filter is ignored, so
     * find(null, null, null) returns everything. Only "$1, $2..." placeholders are added to the SQL text;
     * the filter values themselves are always sent as parameters.
     */
    public Future<List<Payment>> find(UUID tenantId, PaymentStatus status, YearMonth rentMonth) {
        Filter filter = filter(tenantId, status, rentMonth);
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM payments" + filter.where() + NEWEST_FIRST)
                .execute(filter.params())
                .map(rows -> DbUtils.mapAll(rows, PaymentRepository::toPayment));
    }

    /** Same filters as find(), one page at a time. */
    public Future<Page<Payment>> findPage(UUID tenantId, PaymentStatus status, YearMonth rentMonth, PageRequest request) {
        Filter filter = filter(tenantId, status, rentMonth);
        return DbUtils.page(pool, "SELECT count(*) FROM payments" + filter.where(),
                "SELECT " + COLUMNS + " FROM payments" + filter.where() + NEWEST_FIRST,
                filter.params(), request, PaymentRepository::toPayment);
    }

    /** A WHERE clause made only of "$1, $2..." placeholders, and the values that go with them. */
    private record Filter(String where, Tuple params) {
    }

    private static Filter filter(UUID tenantId, PaymentStatus status, YearMonth rentMonth) {
        List<String> conditions = new ArrayList<>();
        Tuple params = Tuple.tuple();
        if (tenantId != null) {
            params.addValue(tenantId);
            conditions.add("tenant_id = $" + params.size());
        }
        if (status != null) {
            params.addValue(status.name());
            conditions.add("status = $" + params.size());
        }
        if (rentMonth != null) {
            params.addValue(rentMonth.toString());
            conditions.add("rent_month = $" + params.size());
        }
        return new Filter(conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions), params);
    }

    /** Corrects a payment. The tenant is never changed. Returns empty if no payment has this id. */
    public Future<Optional<Payment>> update(UUID id, BigDecimal amount, YearMonth rentMonth, LocalDate paymentDate,
                                            PaymentMethod paymentMethod, PaymentStatus status, String receiptId) {
        return pool.preparedQuery("""
                        UPDATE payments
                        SET amount = $2, rent_month = $3, payment_date = $4, payment_method = $5,
                            status = $6, receipt_id = $7, updated_at = now()
                        WHERE id = $1
                        RETURNING\s""" + COLUMNS)
                .execute(Tuple.of(id, amount, rentMonth.toString(), paymentDate, nameOrNull(paymentMethod),
                        status.name(), receiptId))
                .map(rows -> DbUtils.firstRow(rows).map(PaymentRepository::toPayment))
                .recover(err -> Future.failedFuture(translateWriteError(err)));
    }

    private static Throwable translateWriteError(Throwable err) {
        if (DbUtils.isUniqueViolation(err)) {
            // Two different unique indexes protect against duplicates (see V4 migration)
            return "uq_payments_receipt".equals(DbUtils.violatedConstraint(err))
                    ? new ConflictException("A payment with this receiptId already exists")
                    : new ConflictException("Tenant already has a PENDING payment for this month");
        }
        if (DbUtils.isForeignKeyViolation(err)) {
            return new NotFoundException("Tenant not found");
        }
        return err;
    }

    private static String nameOrNull(PaymentMethod paymentMethod) {
        return paymentMethod == null ? null : paymentMethod.name();
    }

    private static Payment toPayment(Row row) {
        String method = row.getString("payment_method");
        return new Payment(
                row.getUUID("id"),
                row.getUUID("tenant_id"),
                row.getBigDecimal("amount"),
                YearMonth.parse(row.getString("rent_month")),
                row.getLocalDate("payment_date"),
                method == null ? null : PaymentMethod.valueOf(method),
                PaymentStatus.valueOf(row.getString("status")),
                row.getString("receipt_id"),
                row.getOffsetDateTime("created_at").toInstant(),
                row.getOffsetDateTime("updated_at").toInstant());
    }
}
