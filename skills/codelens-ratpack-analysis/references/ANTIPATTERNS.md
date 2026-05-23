# Anti-Patterns Reference

CodeLens detects these anti-patterns that complicate migration and indicate code quality issues.

## BLOCKING_JDBC

**Severity:** ERROR

**Description:** JDBC calls made outside `Blocking.get()`, blocking the compute thread.

**Detection:**
```java
// Bad - blocks compute thread
public void handle(Context ctx) {
    List<User> users = jdbcTemplate.query("SELECT * FROM users", mapper);
    ctx.render(users);
}
```

**Fix:**
```java
// Good - executes on blocking thread
public void handle(Context ctx) {
    Blocking.get(() -> jdbcTemplate.query("SELECT * FROM users", mapper))
        .then(users -> ctx.render(users));
}
```

**Migration note:** Target framework likely has native async database support.

---

## THREAD_SLEEP

**Severity:** ERROR

**Description:** `Thread.sleep()` calls block the compute thread.

**Detection:**
```java
// Bad
public void handle(Context ctx) {
    Thread.sleep(1000); // Blocks compute thread!
    ctx.render("delayed");
}
```

**Fix:**
```java
// Good - use Ratpack's execution model
public void handle(Context ctx) {
    ctx.getExecution().sleep(Duration.ofSeconds(1))
        .then(() -> ctx.render("delayed"));
}
```

**Migration note:** Most frameworks have scheduling/delay mechanisms.

---

## SYNCHRONOUS_FILE_IO

**Severity:** WARNING

**Description:** File I/O operations blocking compute threads.

**Detection:**
```java
// Bad
String content = Files.readString(path);
```

**Fix:**
```java
// Good
Blocking.get(() -> Files.readString(path))
    .then(content -> { ... });
```

**Migration note:** Consider async file I/O or move to blocking thread pool.

---

## BLOCKING_HTTP_CLIENT

**Severity:** WARNING

**Description:** Synchronous HTTP clients (OkHttp sync, Apache HttpClient) blocking compute threads.

**Detection:**
```java
// Bad - synchronous call
Response response = okHttpClient.newCall(request).execute();
```

**Fix:**
```java
// Option 1: Use Ratpack's async HTTP client
httpClient.get(uri).then(response -> { ... });

// Option 2: Wrap in Blocking
Blocking.get(() -> okHttpClient.newCall(request).execute())
    .then(response -> { ... });
```

**Migration note:** Target framework likely has async HTTP client support.

---

## CONSOLE_LOGGING

**Severity:** INFO

**Description:** `System.out` or `System.err` usage instead of proper logging.

**Detection:**
```java
System.out.println("Processing user: " + userId);
System.err.println("Error occurred");
```

**Fix:**
```java
private static final Logger log = LoggerFactory.getLogger(MyHandler.class);

log.info("Processing user: {}", userId);
log.error("Error occurred", exception);
```

**Migration note:** Ensure proper logging framework configuration.

---

## SWALLOWED_EXCEPTION

**Severity:** WARNING

**Description:** Empty catch blocks that hide errors.

**Detection:**
```java
try {
    riskyOperation();
} catch (Exception e) {
    // Swallowed - no handling!
}
```

**Fix:**
```java
try {
    riskyOperation();
} catch (Exception e) {
    log.error("Operation failed", e);
    throw new ServiceException("Operation failed", e);
}
```

**Migration note:** Proper error handling is essential for debugging and monitoring.

---

## Severity Levels

| Level | Meaning | Action |
|-------|---------|--------|
| INFO | Code smell, low impact | Fix when convenient |
| WARNING | Potential issues | Fix before migration |
| ERROR | Significant problems | Fix immediately |
| CRITICAL | Severe issues | Blocking for migration |

## Prioritization

1. **Fix CRITICAL and ERROR** before migration
2. **Address WARNING** during migration
3. **Clean up INFO** as time permits

## Viewing Anti-Patterns

```bash
# All anti-patterns
codelens antipatterns scan

# Only errors and critical
codelens antipatterns scan --severity ERROR

# Specific type
codelens antipatterns scan --type BLOCKING_JDBC

# For specific class
codelens antipatterns show com.example.ProblematicHandler
```
