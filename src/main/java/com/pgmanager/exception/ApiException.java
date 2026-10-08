package com.pgmanager.exception;

/**
 * Base class for errors that map to a specific HTTP status.
 * Its message is shown to the client, so it must never contain internal details.
 */
public class ApiException extends RuntimeException {

    private final int statusCode;

    public ApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
