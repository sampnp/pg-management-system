package com.pgmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Body of PATCH /api/admin/users/:id/active. Boolean (not boolean) so a missing value is a clear 400. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountStatusRequest(Boolean active) {
}
