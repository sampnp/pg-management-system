package com.pgmanager.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/**
 * A row of the payments table. Also returned directly as JSON.
 * rentMonth is the month the money is for (e.g. 2026-10); paymentDate is the day it was paid.
 * paymentDate and paymentMethod may be null for a PENDING payment.
 */
public record Payment(
        UUID id,
        UUID tenantId,
        BigDecimal amount,
        YearMonth rentMonth,
        LocalDate paymentDate,
        PaymentMethod paymentMethod,
        PaymentStatus status,
        String receiptId,
        Instant createdAt,
        Instant updatedAt) {
}
