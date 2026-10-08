package com.pgmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Body for creating (POST) and correcting (PUT) a payment.
 * Dates, month and enums are Strings so invalid values give a clear 400 message instead of a JSON parse error.
 * On PUT, tenantId may be left out; if given, it must be the payment's current tenant.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentRequest(
        String tenantId,
        BigDecimal amount,
        String rentMonth,
        String paymentDate,
        String paymentMethod,
        String status,
        String receiptId) {
}
