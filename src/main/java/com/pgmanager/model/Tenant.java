package com.pgmanager.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A row of the tenants table. Also returned directly as JSON.
 * Money uses BigDecimal, never double: 0.1 + 0.2 is not exactly 0.3 in floating point.
 */
public record Tenant(
        UUID id,
        String name,
        String phone,
        String email,
        LocalDate joiningDate,
        BigDecimal monthlyRent,
        BigDecimal securityDeposit,
        TenantStatus status,
        Instant createdAt) {
}
