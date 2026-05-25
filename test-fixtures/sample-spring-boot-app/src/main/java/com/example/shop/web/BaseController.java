package com.example.shop.web;

/** Common base for the REST controllers (exercises a project hierarchy). */
public abstract class BaseController {

    /** Shared helper used by subclasses. */
    protected String resourceName() {
        return getClass().getSimpleName();
    }
}
