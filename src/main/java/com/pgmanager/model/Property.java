package com.pgmanager.model;

import java.time.Instant;
import java.util.UUID;

/** A row of the properties table. Has no sensitive fields, so it is also returned directly as JSON. */
public record Property(UUID id, String name, String address, String city, Instant createdAt) {
}
