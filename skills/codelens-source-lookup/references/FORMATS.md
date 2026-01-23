# Source Output Format Reference

## Full Source (Default)

Complete source code as written or decompiled:

```java
package com.example;

import ratpack.handling.Context;
import ratpack.handling.Handler;

/**
 * Handles user authentication requests.
 */
public class AuthHandler implements Handler {
    private final UserService userService;

    @Inject
    public AuthHandler(UserService userService) {
        this.userService = userService;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        String token = ctx.getRequest().getHeaders().get("Authorization");
        userService.authenticate(token)
            .then(user -> ctx.render(user));
    }
}
```

## Stub Format

Interface-only view - declarations without implementations:

```java
package com.example;

public class AuthHandler implements Handler {
    @Inject
    public AuthHandler(UserService userService);

    @Override
    public void handle(Context ctx) throws Exception;
}
```

**Use cases:**
- Understanding API contracts
- Generating mock implementations
- Documentation generation

## Signatures Format

Minimal method listing:

```
com.example.AuthHandler
  AuthHandler(UserService)
  void handle(Context)
```

**Use cases:**
- Quick reference
- Finding method names
- Overview of large classes

## Javadoc Format

Source with documentation extracted and formatted:

```java
/**
 * Handles user authentication requests.
 *
 * <p>This handler extracts the Authorization header and validates
 * the token against the user service.</p>
 *
 * @see UserService#authenticate(String)
 */
public class AuthHandler implements Handler {

    /**
     * Creates a new auth handler.
     * @param userService the user service for authentication
     */
    @Inject
    public AuthHandler(UserService userService);

    /**
     * Handles the authentication request.
     * @param ctx the request context
     * @throws Exception if authentication fails
     */
    @Override
    public void handle(Context ctx) throws Exception;
}
```

## Kotlin Stub Format

When using `--lang kotlin`:

```kotlin
package com.example

class AuthHandler @Inject constructor(
    private val userService: UserService
) : Handler {

    @Throws(Exception::class)
    override fun handle(ctx: Context)
}
```

## Visibility Filtering Examples

### `--visibility public`

```java
public class AuthHandler implements Handler {
    public AuthHandler(UserService userService);
    public void handle(Context ctx) throws Exception;
}
```

### `--visibility protected`

```java
public class AuthHandler implements Handler {
    public AuthHandler(UserService userService);
    public void handle(Context ctx) throws Exception;
    protected void validateToken(String token);
}
```

### `--visibility all` (default)

```java
public class AuthHandler implements Handler {
    private final UserService userService;

    public AuthHandler(UserService userService);
    public void handle(Context ctx) throws Exception;
    protected void validateToken(String token);
    private String extractToken(Headers headers);
}
```
