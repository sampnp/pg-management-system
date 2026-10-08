package com.pgmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LoginRequest(String email, String password) {

    @Override
    public String toString() {
        return "LoginRequest[email=%s]".formatted(email);
    }
}
