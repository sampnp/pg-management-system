package com.pgmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** bedId is a String so a malformed UUID gives a clear 400 message. The check-in time is set by the server. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CheckInRequest(String bedId) {
}
