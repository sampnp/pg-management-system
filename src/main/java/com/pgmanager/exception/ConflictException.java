package com.pgmanager.exception;

public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(409, message);
    }
}
