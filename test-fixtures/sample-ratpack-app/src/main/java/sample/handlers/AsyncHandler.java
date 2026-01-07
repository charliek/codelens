package sample.handlers;

import ratpack.exec.Blocking;
import ratpack.exec.Execution;
import ratpack.exec.Promise;
import ratpack.handling.Context;
import ratpack.handling.Handler;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Complex async handler - high complexity.
 * Uses Blocking, Promise chaining, and forking.
 */
@Singleton
public class AsyncHandler implements Handler {

    private final UserService userService;
    private final NotificationService notificationService;

    @Inject
    public AsyncHandler(UserService userService, NotificationService notificationService) {
        this.userService = userService;
        this.notificationService = notificationService;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        String userId = ctx.getPathTokens().get("id");
        String action = ctx.getPathTokens().get("action");

        // Complex Promise chain with multiple operations
        fetchUserData(userId)
            .flatMap(user -> processUserAction(user, action))
            .map(this::formatResponse)
            .onError(error -> ctx.render("Error: " + error.getMessage()))
            .then(result -> ctx.render(result));

        // Fork for async notification
        Execution.fork().start(exec -> {
            notificationService.sendNotification(userId, "Action performed: " + action);
        });
    }

    /**
     * Fetches user data using Blocking.
     */
    private Promise<Object> fetchUserData(String userId) {
        return Blocking.get(() -> userService.findUser(userId))
            .cache()
            .mapIf(
                user -> user == null,
                user -> { throw new RuntimeException("User not found"); }
            );
    }

    /**
     * Processes user action asynchronously.
     */
    private Promise<String> processUserAction(Object user, String action) {
        return Promise.async(downstream -> {
            // Simulate async processing
            String result = "Processed " + action + " for " + user;
            downstream.success(result);
        });
    }

    /**
     * Formats the response.
     */
    private String formatResponse(String result) {
        return "{ \"result\": \"" + result + "\" }";
    }
}
