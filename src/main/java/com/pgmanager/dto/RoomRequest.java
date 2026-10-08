package com.pgmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Body for creating and updating a room. capacity is Integer (not int) so a missing value is null, not 0. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoomRequest(String roomNumber, Integer capacity) {
}
