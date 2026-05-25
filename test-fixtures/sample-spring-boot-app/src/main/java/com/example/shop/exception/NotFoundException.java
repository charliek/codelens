package com.example.shop.exception;

/** Thrown when an entity cannot be found. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
