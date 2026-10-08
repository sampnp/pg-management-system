package com.pgmanager.service;

import com.pgmanager.exception.BadRequestException;
import com.pgmanager.model.Role;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.UUID;

/** Input checks shared by the services. Each method throws BadRequestException (400) on invalid input. */
final class Validation {

    // Money columns are NUMERIC(10, 2): at most 99,999,999.99
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("100000000");

    private Validation() {
    }

    static <T> T requireBody(T body) {
        if (body == null) {
            throw new BadRequestException("Request body is required");
        }
        return body;
    }

    /** Returns the trimmed value; rejects null, blank and over-long strings. */
    static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new BadRequestException(field + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }

    /** A UUID sent in a request body, e.g. bedId or tenantId. */
    static UUID requireUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " is required");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(field + " must be a valid UUID");
        }
    }

    /** Parses an ISO date such as 2026-10-09. The caller decides whether the value is required. */
    static LocalDate parseDate(String value, String field) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BadRequestException(field + " must be a valid date in YYYY-MM-DD format");
        }
    }

    /** ADMIN or MANAGER. TENANT accounts are only created for a tenant (POST /api/tenants/:id/account). */
    static Role parseStaffRole(String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("role is required");
        }
        String role = value.trim().toUpperCase(Locale.ROOT);
        if (!role.equals(Role.ADMIN.name()) && !role.equals(Role.MANAGER.name())) {
            throw new BadRequestException("role must be ADMIN or MANAGER");
        }
        return Role.valueOf(role);
    }

    /** A money amount: required, at most 2 decimal places, and positive (or zero when zeroAllowed). */
    static BigDecimal requireAmount(BigDecimal amount, String field, boolean zeroAllowed) {
        if (amount == null) {
            throw new BadRequestException(field + " is required");
        }
        if (zeroAllowed ? amount.signum() < 0 : amount.signum() <= 0) {
            throw new BadRequestException(field + (zeroAllowed ? " cannot be negative" : " must be greater than 0"));
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new BadRequestException(field + " can have at most 2 decimal places");
        }
        if (amount.compareTo(MAX_AMOUNT) >= 0) {
            throw new BadRequestException(field + " is too large");
        }
        return amount;
    }
}
