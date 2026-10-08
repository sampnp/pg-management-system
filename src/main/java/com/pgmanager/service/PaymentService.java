package com.pgmanager.service;

import com.pgmanager.dto.PaymentRequest;
import com.pgmanager.exception.BadRequestException;
import com.pgmanager.exception.NotFoundException;
import com.pgmanager.model.Payment;
import com.pgmanager.model.PaymentMethod;
import com.pgmanager.model.PaymentStatus;
import com.pgmanager.model.Tenant;
import com.pgmanager.repository.PaymentRepository;
import com.pgmanager.repository.TenantRepository;
import io.vertx.core.Future;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Records and corrects rent payments. Payments are independent of check-in/check-out:
 * a checked-out tenant keeps (and can still get) payment records.
 * The amount does not have to equal the tenant's monthly rent - partial payments are normal.
 */
public class PaymentService {

    static final String PAYMENT_NOT_FOUND = "Payment not found";
    private static final int MAX_RECEIPT_ID_LENGTH = 100;

    private final PaymentRepository paymentRepository;
    private final TenantRepository tenantRepository;

    public PaymentService(PaymentRepository paymentRepository, TenantRepository tenantRepository) {
        this.paymentRepository = paymentRepository;
        this.tenantRepository = tenantRepository;
    }

    public Future<Payment> create(PaymentRequest request) {
        return Future.succeededFuture(request)
                .map(r -> validate(r, true))
                .compose(valid -> requireTenant(valid.tenantId())
                        .compose(tenant -> paymentRepository.create(valid.tenantId(), valid.amount(), valid.rentMonth(),
                                valid.paymentDate(), valid.paymentMethod(), valid.status(), valid.receiptId())));
    }

    public Future<Payment> findById(UUID id) {
        return paymentRepository.findById(id)
                .map(payment -> payment.orElseThrow(() -> new NotFoundException(PAYMENT_NOT_FOUND)));
    }

    /** All filters are optional query parameters; the ones that are given are combined with AND. */
    public Future<List<Payment>> list(String tenantId, String status, String rentMonth) {
        // An invalid filter value throws inside compose(), which turns it into a failed Future (-> 400)
        return Future.succeededFuture()
                .compose(v -> paymentRepository.find(
                        isBlank(tenantId) ? null : Validation.requireUuid(tenantId, "tenantId"),
                        isBlank(status) ? null : parseStatus(status),
                        isBlank(rentMonth) ? null : parseRentMonth(rentMonth)));
    }

    /** Corrects a payment's details. The tenant can't be changed, so a payment never moves to someone else's history. */
    public Future<Payment> update(UUID id, PaymentRequest request) {
        return Future.succeededFuture(request)
                .map(r -> validate(r, false))
                .compose(valid -> findById(id)
                        .compose(existing -> {
                            if (valid.tenantId() != null && !valid.tenantId().equals(existing.tenantId())) {
                                return Future.failedFuture(new BadRequestException("tenantId of a payment cannot be changed"));
                            }
                            return paymentRepository.update(id, valid.amount(), valid.rentMonth(), valid.paymentDate(),
                                    valid.paymentMethod(), valid.status(), valid.receiptId());
                        }))
                .map(updated -> updated.orElseThrow(() -> new NotFoundException(PAYMENT_NOT_FOUND)));
    }

    /** A tenant's payments, newest rent month first. 404 for an unknown tenant; empty list if they have none. */
    public Future<List<Payment>> tenantHistory(UUID tenantId) {
        return requireTenant(tenantId)
                .compose(tenant -> paymentRepository.find(tenantId, null, null));
    }

    private Future<Tenant> requireTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .map(tenant -> tenant.orElseThrow(() -> new NotFoundException(TenantService.TENANT_NOT_FOUND)));
    }

    /** Validated and normalized payment fields. tenantId is null on an update that didn't send one. */
    private record PaymentDetails(UUID tenantId, BigDecimal amount, YearMonth rentMonth, LocalDate paymentDate,
                                  PaymentMethod paymentMethod, PaymentStatus status, String receiptId) {
    }

    private static PaymentDetails validate(PaymentRequest request, boolean tenantRequired) {
        Validation.requireBody(request);
        UUID tenantId = (tenantRequired || !isBlank(request.tenantId()))
                ? Validation.requireUuid(request.tenantId(), "tenantId")
                : null;
        BigDecimal amount = Validation.requireAmount(request.amount(), "amount", false);

        if (isBlank(request.rentMonth())) {
            throw new BadRequestException("rentMonth is required");
        }
        YearMonth rentMonth = parseRentMonth(request.rentMonth());

        if (isBlank(request.status())) {
            throw new BadRequestException("status is required");
        }
        PaymentStatus status = parseStatus(request.status());

        PaymentMethod paymentMethod = isBlank(request.paymentMethod()) ? null : parseMethod(request.paymentMethod());
        LocalDate paymentDate = isBlank(request.paymentDate()) ? null : Validation.parseDate(request.paymentDate(), "paymentDate");

        // Money that was received must say when and how (the database enforces this too)
        if (status == PaymentStatus.PAID && paymentDate == null) {
            throw new BadRequestException("paymentDate is required for a PAID payment");
        }
        if (status == PaymentStatus.PAID && paymentMethod == null) {
            throw new BadRequestException("paymentMethod is required for a PAID payment");
        }

        String receiptId = isBlank(request.receiptId()) ? null : request.receiptId().trim();
        if (receiptId != null && receiptId.length() > MAX_RECEIPT_ID_LENGTH) {
            throw new BadRequestException("receiptId must be at most " + MAX_RECEIPT_ID_LENGTH + " characters");
        }
        return new PaymentDetails(tenantId, amount, rentMonth, paymentDate, paymentMethod, status, receiptId);
    }

    /** Exactly "YYYY-MM" with a real month (01-12), e.g. 2026-10. */
    private static YearMonth parseRentMonth(String value) {
        String trimmed = value.trim();
        try {
            if (!trimmed.matches("\\d{4}-\\d{2}")) {
                throw new DateTimeParseException("not YYYY-MM", trimmed, 0);
            }
            return YearMonth.parse(trimmed);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("rentMonth must be in YYYY-MM format");
        }
    }

    private static PaymentStatus parseStatus(String value) {
        try {
            return PaymentStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("status must be PAID or PENDING");
        }
    }

    private static PaymentMethod parseMethod(String value) {
        try {
            return PaymentMethod.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("paymentMethod must be UPI, CASH or BANK_TRANSFER");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
