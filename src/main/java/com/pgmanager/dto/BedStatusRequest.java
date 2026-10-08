package com.pgmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** status is a String so an invalid value gives a clear 400 message (same approach as RoleRequest.role). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BedStatusRequest(String status) {
}
