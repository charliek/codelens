# Ratpack Concepts Reference

This is the Ratpack knowledge the skill's recipes rely on — the handler shapes, the
Promise/exec API, Chain routing, and Guice patterns. CodeLens has no notion of these
concepts; it returns bytecode facts and you classify them by reading the source against
this catalog.

## Handler Types

Ratpack handlers take these four shapes. Find the class-backed ones with
`codelens classes implementations ratpack.handling.Handler` (and the chain/Groovy
interfaces below), then read the body with `codelens source show <fqn>` to classify which
shape each is:

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

Handler that configures a chain of sub-handlers (implements `ratpack.func.Action<Chain>`).
Find these with `codelens classes implementations ratpack.func.Action`, then read the route
registrations from bytecode with `codelens calls <fqn> --method execute` (see the Routes
recipe in `SKILL.md`):

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

Lambda or anonymous class handler defined inline. These compile to `invokedynamic` and have
no class of their own, so `implementations` won't list them — find them inside the chain
that registers them (`codelens calls <chain-fqn> --method execute`). Each shows up as an
`"invokeDynamic": true` call site whose `implMethodName` (e.g. `lambda$execute$0`) is the
synthetic method holding the handler body; run `calls <chain-fqn> --method lambda$execute$0`
to read what it does:

```java
chain.path("hello", ctx -> ctx.render("Hello"));
```

### GROOVY_HANDLER

Handlers written in Groovy with Ratpack's Groovy DSL; find class-backed ones with
`codelens classes implementations ratpack.groovy.handling.GroovyHandler`.

## Promise Patterns

This is the Promise/exec vocabulary you map bytecode facts onto. Surface usage with
`codelens xref ratpack.exec.Blocking` / `xref ratpack.exec.Promise` /
`xref ratpack.exec.Execution` and `codelens methods search --return-type ratpack.exec.Promise`;
see exactly which operators a method calls with `codelens calls <fqn> --method <m>` filtered
to `ratpack.exec`. The tables below are how you read those calls, not categories CodeLens
emits.

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

Find modules with `codelens classes implementations com.google.inject.AbstractModule` (and
`com.google.inject.Module`), read their bindings with `codelens calls <module-fqn> --method configure`,
and list `@Provides` methods with `codelens methods search --annotation com.google.inject.Provides`.
The module/injection shapes below tell you what those `bind`/`to`/`toInstance` calls mean.

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

There is no integration detector — `codelens xref <library-type>` lists every project class
that touches a given client/driver, and you classify it against the patterns below (e.g.
`xref ratpack.http.client.HttpClient`, `xref javax.sql.DataSource`).

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
