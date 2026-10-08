package com.pgmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Body for creating (POST) and updating (PUT) a property - both need the same fields. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PropertyRequest(String name, String address, String city) {
}
