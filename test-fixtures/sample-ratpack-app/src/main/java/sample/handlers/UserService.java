package sample.handlers;

/**
 * Simple service interface for testing DI detection.
 */
public interface UserService {
    Object findUser(String userId);
    void saveUser(Object user);
}
