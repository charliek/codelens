package com.example.shop.web;

import com.example.shop.exception.NotFoundException;
import com.example.shop.exception.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Centralized exception handling. {@code @RestControllerAdvice} is a
 * meta-annotation of {@code @ControllerAdvice} + {@code @ResponseBody}; each
 * {@code @ExceptionHandler} maps a thrown type to an HTTP status via
 * {@code @ResponseStatus}. Surface with
 * {@code codelens annotations usages org.springframework.web.bind.annotation.ExceptionHandler}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NotFoundException ex) {
        return ex.getMessage();
    }

    @ExceptionHandler(ValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleValidation(ValidationException ex) {
        return ex.getMessage();
    }
}
