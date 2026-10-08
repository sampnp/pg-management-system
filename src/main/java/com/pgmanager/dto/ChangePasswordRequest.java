package com.pgmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Body of PATCH /api/auth/password. Always changes the caller's own password (taken from the token). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChangePasswordRequest(String currentPassword, String newPassword) {

    @Override
    public String toString() {
        return "ChangePasswordRequest[***]";
    }
}
