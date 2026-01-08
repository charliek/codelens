package sample.handlers;

import ratpack.handling.Context;
import ratpack.handling.Handler;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Handler with intentional anti-patterns for testing.
 * Uses JDBC directly without Blocking wrapper - should trigger BLOCKING_JDBC detection.
 */
@Singleton
public class BadHandler implements Handler {

    // Anti-pattern: JDBC types injected directly
    private final Connection connection;

    @Inject
    public BadHandler(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        // This would block the event loop - bad practice!
        Statement stmt = connection.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT * FROM users");
        ctx.render("Done");
    }
}
