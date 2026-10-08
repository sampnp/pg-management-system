package com.pgmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** role is a String (not the Role enum) so an invalid value gives a clear 400 message instead of a JSON parse error. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoleRequest(String role) {
}
