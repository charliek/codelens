package com.example.shop.exception;

/** Thrown when input validation fails. */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }
}
