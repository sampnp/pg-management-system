package com.pgmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Body of PATCH /api/maintenance/:id/assign: the id of the ADMIN or MANAGER user who will handle the issue. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssignRequest(String assignedTo) {
}
