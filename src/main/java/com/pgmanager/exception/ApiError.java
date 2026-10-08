package com.pgmanager.exception;

import io.netty.handler.codec.http.HttpResponseStatus;

import java.time.Instant;

/** The JSON body of every error response, e.g. {"status":404,"error":"NOT_FOUND","message":"...","timestamp":"..."}. */
public record ApiError(int status, String error, String message, Instant timestamp) {

    public static ApiError of(int status, String message) {
        // "Not Found" -> "NOT_FOUND"
        String error = HttpResponseStatus.valueOf(status).reasonPhrase().toUpperCase().replace(' ', '_');
        return new ApiError(status, error, message, Instant.now());
    }
}
