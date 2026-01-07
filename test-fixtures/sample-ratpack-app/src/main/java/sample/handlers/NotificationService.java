package sample.handlers;

/**
 * Notification service interface for testing DI detection.
 */
public interface NotificationService {
    void sendNotification(String userId, String message);
}
