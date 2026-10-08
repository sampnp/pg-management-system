package com.pgmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Body for creating and updating a bed. Status is changed separately via PATCH /api/beds/:id/status. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BedRequest(String bedNumber) {
}
