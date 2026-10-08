package com.pgmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Body of POST and PUT /api/maintenance. Enum values are Strings so an invalid value gives a clear 400 message.
 * tenantId is required when staff report an issue; a tenant's own issues always use the tenant from their token.
 * Status and assignment are not part of this body - they have their own staff-only endpoints.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MaintenanceRequest(String tenantId, String title, String description, String category, String priority) {
}
