package com.pgmanager.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One stay of a tenant in a bed: a row of tenant_bed_history, plus the bed's room and property
 * so clients don't need extra requests. checkOut is null while the stay is still current.
 */
public record Occupancy(
        UUID id,
        UUID tenantId,
        UUID bedId,
        UUID roomId,
        UUID propertyId,
        Instant checkIn,
        Instant checkOut) {
}
