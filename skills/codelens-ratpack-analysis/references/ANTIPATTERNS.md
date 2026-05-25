# Anti-Patterns Reference

These anti-patterns complicate a migration and indicate code-quality issues. CodeLens does
not detect or classify them — surface candidate call sites with `xref` / `calls`, confirm by
reading the method body with `codelens source show <fqn>` (does the call actually sit on the
compute thread, outside `Blocking.get`?), and judge severity yourself. The severity labels
below are this author's guidance for that judgment, not a tool output.

## BLOCKING_JDBC

**Severity (guidance):** ERROR

**Description:** JDBC calls made outside `Blocking.get()`, blocking the compute thread.

**How to find it:** `codelens xref java.sql.Connection` / `codelens xref javax.sql.DataSource`
lists the classes that touch JDBC; read the body with `codelens source show <fqn>` to confirm
the call is not wrapped in `Blocking.get`.

**Bad:**
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

**Severity (guidance):** ERROR

**Description:** `Thread.sleep()` calls block the compute thread.

**How to find it:** `codelens calls <fqn> --json` filtered to
`java.lang.Thread.sleep` (`select(.ownerType=="java.lang.Thread" and .methodName=="sleep")`).

**Bad:**
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

**Severity (guidance):** WARNING

**Description:** File I/O operations blocking compute threads.

**How to find it:** `codelens xref java.io.FileInputStream` /
`codelens xref java.nio.file.Files`, then read the body with `codelens source show <fqn>` to
confirm it is not wrapped in `Blocking.get`.

**Bad:**
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

**Severity (guidance):** WARNING

**Description:** Synchronous HTTP clients (OkHttp sync, Apache HttpClient) blocking compute threads.

**How to find it:** `xref` the client type in use, e.g. `codelens xref okhttp3.OkHttpClient`
or `codelens xref org.apache.http.client.HttpClient`; confirm the `.execute()` call is on the
compute thread by reading `codelens source show <fqn>`.

**Bad:**
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

**Severity (guidance):** INFO

**Description:** `System.out` or `System.err` usage instead of proper logging.

**How to find it:** `codelens calls <fqn> --json` filtered to `java.io.PrintStream`
`println`/`print` (`System.out`/`System.err` resolve to `PrintStream` in bytecode).

**Bad:**
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

**Severity (guidance):** WARNING

**Description:** Empty catch blocks that hide errors.

**How to find it:** Empty catch blocks aren't reliably visible from call/reference facts —
read the method body with `codelens source show <fqn>` (prioritize handlers/services you've
already surfaced) and look for catch clauses with no logging or rethrow.

**Bad:**
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

A suggested scale for the severity you assign after reading the confirmed call site — not a
classification CodeLens emits:

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

## Surfacing candidates

There is no anti-pattern scanner; build the inventory yourself from the general primitives,
then confirm and rate each by reading the body:

```bash
# Blocking I/O candidates (then read the body to confirm it's on the compute thread):
codelens xref java.sql.Connection
codelens xref javax.sql.DataSource
codelens xref java.net.HttpURLConnection
codelens xref java.io.FileInputStream

# Thread.sleep and console logging in a class body:
codelens calls com.example.ProblematicHandler --json \
  | jq '.methods[].calls[]
        | select((.ownerType=="java.lang.Thread" and .methodName=="sleep")
              or (.ownerType=="java.io.PrintStream" and (.methodName=="println" or .methodName=="print")))'

# Read the full body to confirm wrapping, swallowed catches, etc.:
codelens source show com.example.ProblematicHandler
```
