package com.pgmanager.model;

import java.util.UUID;

/** A row of the rooms table. Also returned directly as JSON. */
public record Room(UUID id, UUID propertyId, String roomNumber, int capacity) {
}
