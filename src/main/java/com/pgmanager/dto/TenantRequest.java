package com.pgmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Body for creating and updating a tenant. Status, bed and check-in dates are NOT here on purpose:
 * they only change through check-in/check-out, so occupancy data can't become inconsistent.
 * joiningDate is a String so an invalid date gives a clear 400 message instead of a JSON parse error.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TenantRequest(
        String name,
        String phone,
        String email,
        String joiningDate,
        BigDecimal monthlyRent,
        BigDecimal securityDeposit) {
}
