package sample.api;

import ratpack.func.Action;
import ratpack.handling.Chain;
import sample.handlers.SimpleHandler;
import sample.handlers.BlockingHandler;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * API routes for users - implements Action<Chain> for route detection testing.
 */
@Singleton
public class UsersApi implements Action<Chain> {

    private final SimpleHandler simpleHandler;
    private final BlockingHandler blockingHandler;

    @Inject
    public UsersApi(SimpleHandler simpleHandler, BlockingHandler blockingHandler) {
        this.simpleHandler = simpleHandler;
        this.blockingHandler = blockingHandler;
    }

    @Override
    public void execute(Chain chain) throws Exception {
        chain.get(simpleHandler);
        chain.get(":id", blockingHandler);
        chain.post(ctx -> ctx.render("Created"));
        chain.delete(":id", ctx -> ctx.render("Deleted"));

        chain.prefix("admin", adminChain -> {
            adminChain.get(ctx -> ctx.render("Admin list"));
            adminChain.post(ctx -> ctx.render("Admin created"));
        });
    }
}
