# Ratpack Concepts Reference

## Handler Types

CodeLens identifies four handler types:

### HANDLER

Standard handler implementing `ratpack.handling.Handler`:

```java
public class MyHandler implements Handler {
    @Override
    public void handle(Context ctx) throws Exception {
        ctx.render("Hello");
    }
}
```

### CHAIN_ACTION

Handler that configures a chain of sub-handlers:

```java
public class ApiChain implements Action<Chain> {
    @Override
    public void execute(Chain chain) throws Exception {
        chain.path("users", ctx -> ctx.render("users"));
        chain.path("orders", ctx -> ctx.render("orders"));
    }
}
```

### INLINE_HANDLER

Lambda or anonymous class handler defined inline:

```java
chain.path("hello", ctx -> ctx.render("Hello"));
```

### GROOVY_HANDLER

Handlers written in Groovy with Ratpack's Groovy DSL.

## Promise Patterns

### Blocking Operations

`Blocking.get()` executes blocking code on a separate thread pool:

```java
Blocking.get(() -> {
    return database.query("SELECT * FROM users");
}).then(users -> {
    ctx.render(users);
});
```

`Blocking.on()` specifies an executor:

```java
Blocking.on(executor, () -> expensiveOperation())
```

### Promise Creation

| Method | Use Case |
|--------|----------|
| `Promise.async()` | Wrap callback-based APIs |
| `Promise.sync()` | Wrap synchronous values (use sparingly) |
| `Promise.value()` | Create Promise from existing value |

### Promise Operators

| Operator | Purpose |
|----------|---------|
| `map()` | Transform value synchronously |
| `flatMap()` | Chain to another Promise |
| `then()` | Terminal operation, consume value |
| `onError()` | Handle errors |
| `route()` | Conditional branching |
| `cache()` | Cache result |
| `retry()` | Retry on failure |

### Fork and ParallelBatch

`fork()` executes Promise on compute thread:

```java
promise.fork().then(result -> { ... });
```

`ParallelBatch` executes multiple Promises concurrently:

```java
ParallelBatch.of(promise1, promise2, promise3)
    .yield()
    .then(results -> { ... });
```

## Guice Integration

### Module Types

**AbstractModule** - Standard Guice module:

```java
public class ServiceModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(UserService.class).to(UserServiceImpl.class);
    }

    @Provides
    @Singleton
    public Database provideDatabase() {
        return new Database();
    }
}
```

**ConfigurableModule** - Ratpack-specific module with configuration:

```java
public class ApiModule extends ConfigurableModule<ApiConfig> {
    @Override
    protected void configure() {
        bind(ApiClient.class).toInstance(
            new ApiClient(getConfig().getBaseUrl())
        );
    }
}
```

### Injection Patterns

**Constructor Injection** (preferred):

```java
public class MyHandler implements Handler {
    private final UserService userService;

    @Inject
    public MyHandler(UserService userService) {
        this.userService = userService;
    }
}
```

**Field Injection** (avoid if possible):

```java
public class MyHandler implements Handler {
    @Inject
    private UserService userService;
}
```

## Context API

The `Context` object provides access to:

| Method | Purpose |
|--------|---------|
| `render()` | Send response |
| `next()` | Pass to next handler |
| `insert()` | Insert handlers |
| `getRequest()` | Access request details |
| `getResponse()` | Access response |
| `get(Class)` | Get registered object |
| `parse(Class)` | Parse request body |

## Execution Model

Ratpack uses an event-driven, non-blocking model:

1. **Compute threads** - Handle request processing (limited pool)
2. **Blocking threads** - Execute blocking operations (larger pool)
3. **Event loop** - Manages I/O operations

**Key principle:** Never block compute threads. Use `Blocking.get()` for any blocking operation.

## Common Integrations

### HTTP Clients

```java
// Ratpack HttpClient (non-blocking)
httpClient.get(uri).then(response -> { ... });

// External clients should use Blocking.get()
Blocking.get(() -> okHttpClient.newCall(request).execute())
```

### Databases

```java
// Always wrap JDBC in Blocking
Blocking.get(() -> {
    try (Connection conn = dataSource.getConnection()) {
        return queryUsers(conn);
    }
})
```

### Async Libraries

DynamoDB async, reactive drivers, etc. can return Promises directly or be wrapped:

```java
Promise.async(downstream -> {
    asyncClient.getItem(request, (result, error) -> {
        if (error != null) downstream.error(error);
        else downstream.success(result);
    });
});
```
