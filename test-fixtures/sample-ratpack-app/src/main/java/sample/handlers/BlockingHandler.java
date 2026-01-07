package sample.handlers;

import ratpack.exec.Blocking;
import ratpack.exec.Promise;
import ratpack.handling.Context;
import ratpack.handling.Handler;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handler using Blocking.get() - medium complexity.
 * Uses Blocking for database/IO operations.
 */
@Singleton
public class BlockingHandler implements Handler {

    private final UserService userService;

    @Inject
    public BlockingHandler(UserService userService) {
        this.userService = userService;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        String userId = ctx.getPathTokens().get("id");

        Blocking.get(() -> userService.findUser(userId))
            .map(user -> user != null ? user : "User not found")
            .then(result -> ctx.render(result.toString()));
    }

    /**
     * Helper method that returns a Promise.
     */
    public Promise<String> getUserName(String userId) {
        return Blocking.get(() -> userService.findUser(userId))
            .map(Object::toString);
    }
}
