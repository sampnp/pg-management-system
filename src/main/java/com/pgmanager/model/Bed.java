package com.pgmanager.model;

import java.util.UUID;

/** A row of the beds table. Also returned directly as JSON. */
public record Bed(UUID id, UUID roomId, String bedNumber, BedStatus status) {
}
