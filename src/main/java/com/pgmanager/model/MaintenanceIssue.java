package com.pgmanager.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of maintenance_issues, plus the room and property of its bed so clients don't need extra requests.
 * bedId is where the tenant lived when they reported the issue; it does not change when they check out.
 * assignedTo is the staff user handling it (null until assigned). resolvedAt is set while RESOLVED or CLOSED.
 */
public record MaintenanceIssue(
        UUID id,
        UUID tenantId,
        UUID bedId,
        UUID roomId,
        UUID propertyId,
        String title,
        String description,
        MaintenanceCategory category,
        MaintenancePriority priority,
        MaintenanceStatus status,
        UUID assignedTo,
        Instant createdAt,
        Instant updatedAt,
        Instant resolvedAt) {
}
