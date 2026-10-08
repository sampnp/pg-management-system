package com.pgmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** status is a String so an invalid value gives a clear 400 message instead of a JSON parse error. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MaintenanceStatusRequest(String status) {
}
