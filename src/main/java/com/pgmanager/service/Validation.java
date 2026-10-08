package com.pgmanager.service;

import com.pgmanager.exception.BadRequestException;

/** Input checks shared by the services. Each method throws BadRequestException (400) on invalid input. */
final class Validation {

    private Validation() {
    }

    static <T> T requireBody(T body) {
        if (body == null) {
            throw new BadRequestException("Request body is required");
        }
        return body;
    }

    /** Returns the trimmed value; rejects null, blank and over-long strings. */
    static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new BadRequestException(field + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }
}
