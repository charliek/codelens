package sample.modules;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import sample.handlers.*;

/**
 * Main Guice module for the application.
 * Demonstrates various binding patterns.
 */
public class AppModule extends AbstractModule {

    @Override
    protected void configure() {
        // Handler bindings
        bind(SimpleHandler.class);
        bind(BlockingHandler.class);
        bind(AsyncHandler.class);
    }

    @Provides
    @Singleton
    public UserService provideUserService() {
        return new UserService() {
            @Override
            public Object findUser(String userId) {
                return "User:" + userId;
            }

            @Override
            public void saveUser(Object user) {
                // no-op
            }
        };
    }

    @Provides
    @Singleton
    public NotificationService provideNotificationService() {
        return (userId, message) -> {
            System.out.println("Notification to " + userId + ": " + message);
        };
    }
}
