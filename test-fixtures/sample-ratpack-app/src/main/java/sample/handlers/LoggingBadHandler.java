package sample.handlers;

import ratpack.handling.Context;
import ratpack.handling.Handler;

import java.io.PrintStream;

/**
 * Handler with console logging anti-pattern for testing.
 * Uses System.out/PrintStream instead of a proper logger.
 */
public class LoggingBadHandler implements Handler {

    // Anti-pattern: PrintStream field (console logging)
    private final PrintStream output;

    public LoggingBadHandler() {
        this.output = System.out;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        output.println("Handling request: " + ctx.getRequest().getPath());
        ctx.render("Done");
    }
}
