package sample.handlers;

import ratpack.handling.Context;
import ratpack.handling.Handler;

import javax.inject.Singleton;

/**
 * Simple handler - low complexity.
 * Implements Handler directly, no Promise usage.
 */
@Singleton
public class SimpleHandler implements Handler {

    @Override
    public void handle(Context ctx) throws Exception {
        ctx.render("Hello, World!");
    }
}
