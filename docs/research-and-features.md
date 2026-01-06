# CodeLens: Research Analysis & Feature Specification

## Executive Summary

This document synthesizes research on Ratpack framework patterns, migration target frameworks (Spring MVC, Spring WebFlux, Micronaut), and Kotlin coroutines to establish CodeLens feature requirements. The goal is to create a tool that enables both general-purpose codebase analysis and deep support for Ratpack migration specifically.

**Companion Document:** See `codelens-phase1-spec.md` for the implementation plan.

---

## Part 1: Ratpack Framework Analysis

### 1.1 Core Architecture Patterns

Ratpack is fundamentally **asynchronous and non-blocking**, built on Netty. Understanding these patterns is critical for migration:

#### Execution Model
```
┌─────────────────────────────────────────────────────────────────┐
│                    Ratpack Execution Model                      │
├─────────────────────────────────────────────────────────────────┤
│  Compute Threads (Event Loop)    │  Blocking Thread Pool       │
│  - Small, fixed size             │  - Cached/expandable        │
│  - Handle HTTP I/O               │  - For JDBC, file I/O       │
│  - Must never block              │  - Blocking.get() schedules │
│  - Promise chains execute here   │    work here                │
└──────────────────────────────────┴──────────────────────────────┘
```

**Key Insight for Migration:** Ratpack's dual-thread-pool model maps directly to:
- Spring WebFlux: Schedulers.parallel() vs Schedulers.boundedElastic()
- Kotlin coroutines: Dispatchers.Default vs Dispatchers.IO
- Micronaut: @Blocking annotation or explicit executor selection

#### Handler Pipeline
Ratpack handlers form a **compositional pipeline** where any handler can:
1. Generate a response (terminal)
2. Delegate to the next handler via `ctx.next()`
3. Insert handlers dynamically via `ctx.insert()`

```java
// Ratpack handler chain
handlers { chain ->
    chain.all(new AuthHandler())           // Middleware pattern
         .prefix("api") {
             it.get("users", new UserHandler())
         }
}
```

**Migration Mapping:**
| Ratpack | Spring MVC | Spring WebFlux | Micronaut |
|---------|-----------|----------------|-----------|
| Handler | @Controller method | @Controller method | @Controller method |
| ctx.next() | Filter chain | WebFilter chain | Filter chain |
| Chain.prefix() | @RequestMapping | RouterFunction | @Controller path |
| ctx.insert() | No direct equivalent | No direct equivalent | No direct equivalent |

### 1.2 Promise API Patterns

The `Promise<T>` is Ratpack's core async abstraction. Understanding Promise patterns is essential for migration complexity assessment.

#### Promise Creation Patterns
```java
// 1. From blocking operation (MOST COMMON - migration complexity: MEDIUM)
Promise<User> user = Blocking.get(() -> userDao.findById(id));

// 2. From already-available value (migration complexity: LOW)
Promise<String> value = Promise.value("hello");

// 3. From async callback (migration complexity: HIGH)
Promise<Response> response = Promise.async(downstream -> {
    httpClient.send(request, result -> downstream.success(result));
});

// 4. From execution fork (migration complexity: VERY HIGH)
Promise<r> forked = Execution.fork().start(exec -> {
    // Runs in separate execution
});
```

#### Promise Operators (Critical for Migration Mapping)
| Ratpack Promise | Reactor Mono/Flux | Kotlin Coroutines | Description |
|-----------------|-------------------|-------------------|-------------|
| `.map(fn)` | `.map(fn)` | Direct code | Sync transform |
| `.flatMap(fn)` | `.flatMap(fn)` | `suspend` call | Async transform |
| `.then(action)` | `.subscribe()` | Implicit | Terminal consumption |
| `.blockingMap(fn)` | `.publishOn(elastic).map()` | `withContext(IO)` | Run on blocking pool |
| `.flatRight(fn)` | `.zipWith()` | `async{}` + destructure | Parallel + combine |
| `.onError(handler)` | `.onErrorResume()` | `try/catch` | Error handling |
| `.route(pred, action)` | `.filter().switchIfEmpty()` | `if` statement | Conditional routing |
| `.cache()` | `.cache()` | Manual caching | Memoization |
| `.throttle(n)` | `.limitRate(n)` | `Semaphore` | Backpressure |

#### Complex Promise Patterns to Detect

**Pattern 1: Sequential Dependencies**
```java
// Ratpack
userService.findById(id)
    .flatMap(user -> orderService.getOrders(user.getId()))
    .flatMap(orders -> billingService.calculate(orders))
    .then(ctx::render);

// Kotlin coroutines equivalent (simpler!)
val user = userService.findById(id)
val orders = orderService.getOrders(user.id)
val billing = billingService.calculate(orders)
ctx.render(billing)
```

**Pattern 2: Parallel Execution**
```java
// Ratpack
Promise.value(userId)
    .left(userService.findById(userId))
    .right(roleService.getRoles(userId))
    .flatRight(pair -> permissionService.getPermissions(pair.right))
    .then(result -> ctx.render(result));

// Kotlin coroutines equivalent
coroutineScope {
    val user = async { userService.findById(userId) }
    val roles = async { roleService.getRoles(userId) }
    val permissions = async { permissionService.getPermissions(roles.await()) }
    ctx.render(Triple(user.await(), roles.await(), permissions.await()))
}
```

**Pattern 3: Execution Fork (Hardest to Migrate)**
```java
// Ratpack - creates new execution context
Execution.fork()
    .onComplete(exec -> cleanup())
    .start(exec -> {
        // Completely separate execution
        backgroundJob.run();
    });

// Spring WebFlux - no direct equivalent, need manual scheduling
// Kotlin - launch in separate scope
```

### 1.3 Dependency Injection Patterns

Ratpack uses **Google Guice** with a **Registry** abstraction that provides request-scoped lookup.

#### Registry Access Patterns
```java
// Pattern 1: Constructor injection (clean, testable)
@Singleton
public class UserHandler implements Handler {
    private final UserService userService;
    
    @Inject
    public UserHandler(UserService userService) {
        this.userService = userService;
    }
}

// Pattern 2: Registry lookup (request-scoped, dynamic)
public void handle(Context ctx) {
    UserService service = ctx.get(UserService.class);  // Runtime lookup
    ctx.maybeGet(OptionalService.class).ifPresent(...); // Optional lookup
}

// Pattern 3: Registry insertion (handler-scoped DI)
ctx.insert(
    Registry.single(RequestContext.class, new RequestContext()),
    nextHandler
);
```

**Migration Considerations:**
- Constructor injection → Direct port to Spring/Micronaut
- `ctx.get()` lookup → Need to identify what's being looked up and when
- `ctx.insert()` → Often needs request-scoped beans or method parameters
- `ctx.maybeGet()` → Optional dependency injection

#### Guice Module Patterns
```java
public class AppModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(UserService.class).to(UserServiceImpl.class).in(Singleton.class);
        bind(HttpClient.class).toProvider(HttpClientProvider.class);
    }
    
    @Provides @Singleton
    DataSource dataSource(Config config) {
        return createDataSource(config);
    }
}
```

### 1.4 Common Anti-Patterns to Detect

These indicate migration complexity or bugs:

1. **Blocking on compute thread**
   ```java
   // BAD - blocks event loop
   ctx.render(userDao.findById(id).toString());
   
   // Should use Blocking.get()
   ```

2. **Unsubscribed Promise**
   ```java
   // BAD - Promise never executes
   Promise<Void> p = auditService.log(event);
   ctx.render("done");  // Audit never happens!
   ```

3. **Thread.sleep() anywhere**
   ```java
   // BAD - blocks whatever thread it's on
   Thread.sleep(1000);
   ```

4. **Direct JDBC without Blocking**
   ```java
   // BAD - blocks compute thread
   connection.executeQuery(sql);
   ```

---

## Part 2: Migration Target Frameworks

### 2.1 Spring WebFlux (Reactive)

**When to choose:** When you want to stay reactive and have team familiarity with Reactor.

#### Architectural Mapping
| Ratpack Concept | Spring WebFlux Equivalent |
|-----------------|---------------------------|
| `RatpackServer` | `WebFlux` application |
| `Handler` | `@RestController` or `RouterFunction` |
| `Context` | `ServerRequest` / `ServerResponse` |
| `Promise<T>` | `Mono<T>` |
| `Registry` | Spring DI + `@RequestScope` |
| Guice Module | `@Configuration` |

#### Migration Example
```java
// Ratpack
public class UserHandler implements Handler {
    private final UserRepository repo;
    
    @Override
    public void handle(Context ctx) {
        String id = ctx.getPathTokens().get("id");
        Blocking.get(() -> repo.findById(id))
            .map(User::toDto)
            .then(dto -> ctx.render(json(dto)));
    }
}

// Spring WebFlux
@RestController
public class UserController {
    private final UserRepository repo;  // R2DBC repository
    
    @GetMapping("/users/{id}")
    public Mono<UserDto> getUser(@PathVariable String id) {
        return repo.findById(id)
            .map(User::toDto);
    }
}
```

### 2.2 Micronaut (Modern, Fast Startup)

**When to choose:** When startup time matters (serverless, CLI tools) or for GraalVM native.

#### Architectural Mapping
| Ratpack Concept | Micronaut Equivalent |
|-----------------|----------------------|
| `Handler` | `@Controller` method |
| `Promise<T>` | `Mono<T>` or `suspend fun` |
| Guice Module | `@Factory` |
| `Blocking.get()` | `@Blocking` annotation or Schedulers |

### 2.3 Kotlin Coroutines (Recommended)

**When to choose:** Best overall migration path. Works with Spring WebFlux or Micronaut.

#### Why Coroutines Are Better for Migration

1. **Promise chains become sequential code**
   ```kotlin
   // Before (Ratpack Promise chain)
   userService.findById(id)
       .flatMap { user -> orderService.getOrders(user.id) }
       .flatMap { orders -> billingService.calculate(orders) }
       .then { ctx.render(it) }
   
   // After (Kotlin coroutines)
   val user = userService.findById(id)
   val orders = orderService.getOrders(user.id)
   val billing = billingService.calculate(orders)
   ctx.render(billing)
   ```

2. **Error handling uses standard try/catch**
   ```kotlin
   // Before (Ratpack)
   promise.onError { e -> ctx.error(e) }
   
   // After (Kotlin)
   try {
       val result = suspendingCall()
   } catch (e: Exception) {
       ctx.error(e)
   }
   ```

3. **Parallel execution is explicit**
   ```kotlin
   // Clear parallel semantics
   coroutineScope {
       val user = async { userService.findById(id) }
       val roles = async { roleService.getRoles(id) }
       UserWithRoles(user.await(), roles.await())
   }
   ```

4. **Nullability is explicit**
   ```kotlin
   // Kotlin: User? means nullable
   val user: User? = userService.findById(id)
   
   // vs Reactor: Mono<User> could be empty... maybe?
   ```

---

## Part 3: CodeLens Feature Requirements

### 3.1 General-Purpose Features (Framework-Agnostic)

These features are useful for any JVM codebase analysis:

| Feature | Description | Use Cases |
|---------|-------------|-----------|
| **Class Discovery** | Find classes by package, annotation, supertype | "Find all @Entity classes" |
| **Dependency Graph** | What depends on what | Impact analysis, refactoring |
| **Implementation Finder** | Find concrete implementations | Interface discovery |
| **Annotation Search** | Find annotated elements | Configuration audit |
| **Method Search** | Find methods by signature/annotation | API surface analysis |
| **Type Hierarchy** | Class inheritance trees | Understanding abstractions |
| **Package Analysis** | Package dependencies, cycles | Architecture validation |

### 3.2 Ratpack Migration Features

| Feature | Description | Migration Value |
|---------|-------------|-----------------|
| **Handler Discovery** | Find all Handler implementations | Know what to migrate |
| **Promise Chain Analysis** | Map Promise operator usage | Understand complexity |
| **Blocking.get() Detection** | Find all blocking wrappers | IO boundary identification |
| **Registry Access Analysis** | Find ctx.get() usages | DI refactoring needs |
| **Anti-Pattern Detection** | Find blocking anti-patterns | Fix before migration |
| **Module/Binding Inventory** | Map Guice configuration | Spring config translation |
| **Complexity Scoring** | Rate handlers by migration effort | Prioritization |
| **Migration Report** | Aggregate analysis for planning | Project estimation |

### 3.3 Migration Complexity Algorithm

```kotlin
data class MigrationComplexity(
    val score: Double,
    val level: ComplexityLevel,
    val factors: List<ComplexityFactor>,
    val estimatedEffort: String
)

enum class ComplexityLevel { LOW, MEDIUM, HIGH, VERY_HIGH }

fun calculateComplexity(handler: HandlerAnalysis): MigrationComplexity {
    var score = 1.0  // Base
    val factors = mutableListOf<ComplexityFactor>()
    
    // Promise source complexity
    handler.promiseSources.forEach { source ->
        val (name, value) = when (source) {
            BLOCKING_GET -> "Blocking.get()" to 0.5
            PROMISE_VALUE -> "Promise.value()" to 0.0
            PROMISE_ASYNC -> "Promise.async()" to 2.0
            EXECUTION_FORK -> "Execution.fork()" to 3.0
            HTTP_CLIENT -> "HTTP client" to 0.5
            else -> "Unknown Promise" to 1.0
        }
        score += value
        factors.add(ComplexityFactor(name, value))
    }
    
    // Promise chain depth
    if (handler.promiseChainDepth > 3) {
        val depth = handler.promiseChainDepth - 3
        score += depth * 0.5
        factors.add(ComplexityFactor("Deep Promise chain ($depth)", depth * 0.5))
    }
    
    // Registry access (dynamic DI)
    handler.registryAccess.forEach { access ->
        score += 0.5
        factors.add(ComplexityFactor("ctx.${access.method}(${access.type})", 0.5))
    }
    
    // Anti-patterns
    handler.antiPatterns.forEach { pattern ->
        val value = when (pattern) {
            BLOCKING_JDBC -> 1.5
            THREAD_SLEEP -> 1.0
            UNSUBSCRIBED_PROMISE -> 0.5
        }
        score += value
        factors.add(ComplexityFactor("Anti-pattern: $pattern", value))
    }
    
    val level = when {
        score <= 2 -> LOW
        score <= 4 -> MEDIUM
        score <= 7 -> HIGH
        else -> VERY_HIGH
    }
    
    val effort = when (level) {
        LOW -> "~1 hour"
        MEDIUM -> "~4 hours"
        HIGH -> "~1 day"
        VERY_HIGH -> "~1 week"
    }
    
    return MigrationComplexity(score, level, factors, effort)
}
```

---

## Part 4: Detection Strategies

### 4.1 ClassGraph-Based Detection (Phase 1)

ClassGraph provides bytecode-level analysis without source code. What it CAN detect:

| Detection | How | Reliability |
|-----------|-----|-------------|
| Handler implementations | `getClassesImplementing("ratpack.handling.Handler")` | ✅ High |
| Guice modules | `getSubclasses("AbstractModule")` | ✅ High |
| Blocking usage | `classDependencies.contains("ratpack.exec.Blocking")` | ✅ High |
| Promise type usage | Check method return types/parameters | ✅ High |
| Execution.fork usage | Class dependency on `ratpack.exec.Execution` | ✅ High |
| Registry access | Context in method parameters | ⚠️ Medium (presence only) |
| JDBC direct usage | Dependencies on java.sql.* | ✅ High |

What ClassGraph CANNOT detect (needs source analysis):
- Promise chain depth (how many .flatMap() calls)
- Lambda body contents
- Specific ctx.get() type arguments
- Control flow within methods

### 4.2 Source Analysis (Phase 2+)

For deeper analysis, Phase 2 will add source-level analysis using Kotlin Analysis API or JavaParser:

| Detection | Phase 2 Enhancement |
|-----------|---------------------|
| Promise chain depth | Count chained operators in source |
| Lambda bodies | What happens inside .map(), .flatMap() |
| ctx.get() types | Extract type arguments from calls |
| Unsubscribed Promises | Data flow analysis |

---

## Part 5: API Design Principles

### 5.1 Query Pattern

All analysis queries follow a consistent pattern:

```kotlin
// Request
data class FindHandlersRequest(
    val complexity: ComplexityLevel? = null,  // Optional filter
    val projectOnly: Boolean = true,           // Exclude library classes
    val includeDetails: Boolean = false        // Control response size
)

// Response
data class FindHandlersResponse(
    val handlers: List<HandlerInfo>,
    val summary: HandlerSummary
)

data class HandlerSummary(
    val total: Int,
    val byComplexity: Map<ComplexityLevel, Int>,
    val estimatedTotalEffort: String
)
```

### 5.2 Progressive Disclosure

Responses support different detail levels:

```json
// includeDetails=false (default)
{
  "handlers": [
    {"fqn": "com.example.UserHandler", "complexity": "MEDIUM"}
  ]
}

// includeDetails=true
{
  "handlers": [
    {
      "fqn": "com.example.UserHandler",
      "complexity": {
        "score": 3.5,
        "level": "MEDIUM",
        "factors": [
          {"name": "Blocking.get()", "contribution": 0.5},
          {"name": "Registry lookup", "contribution": 1.0}
        ]
      },
      "promiseUsages": [...],
      "registryAccess": [...]
    }
  ]
}
```

---

## Part 6: Reference Implementation Snippets

### 6.1 ClassGraph Detection

```kotlin
object RatpackTypes {
    const val HANDLER = "ratpack.handling.Handler"
    const val CONTEXT = "ratpack.handling.Context"
    const val PROMISE = "ratpack.exec.Promise"
    const val BLOCKING = "ratpack.exec.Blocking"
    const val EXECUTION = "ratpack.exec.Execution"
    const val CHAIN = "ratpack.handling.Chain"
    const val ACTION = "ratpack.func.Action"
    const val GUICE_MODULE = "com.google.inject.Module"
    const val GUICE_ABSTRACT_MODULE = "com.google.inject.AbstractModule"
    const val CONFIGURABLE_MODULE = "ratpack.guice.ConfigurableModule"
}

// Detection queries
fun findHandlers(scanResult: ScanResult): List<ClassInfo> {
    return scanResult.getClassesImplementing(RatpackTypes.HANDLER)
        .filter { it.isPublic && !it.isAbstract }
}

fun findPromiseUsage(classInfo: ClassInfo): List<MethodInfo> {
    return classInfo.methodInfo.filter { method ->
        method.parameterInfo.any { it.typeDescriptor.toString().contains("Promise") } ||
        method.typeDescriptor.resultType.toString().contains("Promise")
    }
}

fun findBlockingCalls(classInfo: ClassInfo): Boolean {
    return classInfo.classDependencies.any { 
        it.name == RatpackTypes.BLOCKING 
    }
}
```

### 6.2 Complexity Calculation

```kotlin
data class HandlerComplexity(
    val handler: ClassInfo,
    val score: Double,
    val level: ComplexityLevel,
    val factors: List<ComplexityFactor>
)

enum class ComplexityLevel { LOW, MEDIUM, HIGH, VERY_HIGH }

data class ComplexityFactor(
    val name: String,
    val contribution: Double,
    val count: Int
)

fun calculateComplexity(handler: ClassInfo, scanResult: ScanResult): HandlerComplexity {
    val factors = mutableListOf<ComplexityFactor>()
    var score = 1.0  // Base score
    
    // Check for Blocking usage
    val hasBlocking = handler.classDependencies.any { it.name.contains("Blocking") }
    if (hasBlocking) {
        factors.add(ComplexityFactor("Blocking.get()", 0.5, 1))
        score += 0.5
    }
    
    // Check for Execution.fork()
    val hasExecutionFork = handler.classDependencies.any { 
        it.name == "ratpack.exec.Execution" 
    }
    if (hasExecutionFork) {
        factors.add(ComplexityFactor("Execution.fork()", 3.0, 1))
        score += 3.0
    }
    
    // Check for Promise operations via method references
    val promiseMethodCalls = countPromiseOperations(handler)
    if (promiseMethodCalls > 0) {
        val promiseScore = promiseMethodCalls * 0.3
        factors.add(ComplexityFactor("Promise operators", promiseScore, promiseMethodCalls))
        score += promiseScore
    }
    
    val level = when {
        score <= 2 -> ComplexityLevel.LOW
        score <= 4 -> ComplexityLevel.MEDIUM
        score <= 7 -> ComplexityLevel.HIGH
        else -> ComplexityLevel.VERY_HIGH
    }
    
    return HandlerComplexity(handler, score, level, factors)
}
```

---

## Appendix A: Reference Mappings

### A.1 Complete Ratpack → Spring WebFlux Mapping

| Ratpack | Spring WebFlux | Notes |
|---------|---------------|-------|
| `Handler` | `@RestController` | Or `RouterFunction` |
| `Context` | `ServerRequest` | Different API surface |
| `ctx.render(json(x))` | Return `Mono<X>` | Framework handles serialization |
| `ctx.getPathTokens()` | `@PathVariable` | Annotation-based |
| `ctx.getRequest().getQueryParams()` | `@RequestParam` | Annotation-based |
| `ctx.getResponse().status(404)` | `ResponseEntity.notFound()` | Or exception handler |
| `Promise<T>` | `Mono<T>` | Direct mapping |
| `Blocking.get()` | `.subscribeOn(Schedulers.boundedElastic())` | Thread pool switch |
| `Promise.value(x)` | `Mono.just(x)` | Direct |
| `Promise.async()` | `Mono.create()` | Similar pattern |
| `.map()` | `.map()` | Identical |
| `.flatMap()` | `.flatMap()` | Identical |
| `.then()` | `.subscribe()` or controller return | Often implicit |
| `.onError()` | `.onErrorResume()` | Similar |
| `Execution.fork()` | Manual `Schedulers` | No direct equivalent |
| `Registry` | Spring DI + `@RequestScope` | Different model |
| `ctx.get(X.class)` | Constructor injection | Prefer static DI |
| Guice Module | `@Configuration` | Similar concept |
| `@Singleton` | `@Component` | Default Spring scope |

### A.2 Complete Ratpack → Kotlin Coroutines Mapping

| Ratpack | Kotlin Coroutines | Notes |
|---------|-------------------|-------|
| `Promise<T>` | `suspend fun(): T` | Function becomes suspending |
| `Promise.value(x)` | Just `x` | No wrapper needed |
| `Blocking.get { }` | `withContext(Dispatchers.IO) { }` | Explicit dispatcher |
| `Promise.async { downstream -> }` | `suspendCoroutine { cont -> }` | Continuation-based |
| `.map { }` | Direct code | No operator needed |
| `.flatMap { }` | Call suspend function | Sequential by default |
| `.then { }` | Remove entirely | Implicit |
| `.onError { }` | `try/catch` | Standard error handling |
| `.flatRight()` parallel | `async { }` + `await()` | Structured concurrency |
| `Execution.fork()` | `launch { }` in scope | Clean parallel work |
| `Promise<Void>` | `suspend fun(): Unit` | Or just `suspend fun()` |
| Optional results | `T?` | Kotlin nullability |

---

## Appendix B: Example Migration Scenarios

### B.1 Simple Handler (LOW complexity)
```java
// Before (Ratpack)
public class HealthHandler implements Handler {
    @Override
    public void handle(Context ctx) {
        ctx.render(json(Map.of("status", "healthy")));
    }
}

// After (Spring WebFlux)
@RestController
public class HealthController {
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "healthy");
    }
}

// After (Kotlin + Spring)
@RestController
class HealthController {
    @GetMapping("/health")
    fun health() = mapOf("status" to "healthy")
}
```

### B.2 Blocking Database Access (MEDIUM complexity)
```java
// Before (Ratpack)
public class UserHandler implements Handler {
    private final UserRepository repo;
    
    @Inject
    public UserHandler(UserRepository repo) { this.repo = repo; }
    
    @Override
    public void handle(Context ctx) {
        String id = ctx.getPathTokens().get("id");
        Blocking.get(() -> repo.findById(id))
            .map(User::toDto)
            .then(dto -> ctx.render(json(dto)));
    }
}

// After (Spring WebFlux + R2DBC)
@RestController
public class UserController {
    private final UserRepository repo;  // R2DBC repository
    
    @GetMapping("/users/{id}")
    public Mono<UserDto> getUser(@PathVariable String id) {
        return repo.findById(id)  // Already returns Mono
            .map(User::toDto);
    }
}

// After (Kotlin Coroutines + Spring)
@RestController
class UserController(private val repo: UserRepository) {
    @GetMapping("/users/{id}")
    suspend fun getUser(@PathVariable id: String): UserDto? {
        return repo.findById(id)?.toDto()
    }
}
```

### B.3 Complex Parallel Execution (HIGH complexity)
```java
// Before (Ratpack)
public void handle(Context ctx) {
    String userId = ctx.getPathTokens().get("id");
    
    Blocking.get(() -> userRepo.findById(userId))
        .flatRight(user -> Blocking.get(() -> roleRepo.getRoles(user.getId())))
        .flatMap(pair -> {
            User user = pair.left;
            List<Role> roles = pair.right;
            return Blocking.get(() -> permissionRepo.getPermissions(roles))
                .map(perms -> new UserProfile(user, roles, perms));
        })
        .then(profile -> ctx.render(json(profile)));
}

// After (Kotlin Coroutines) - MUCH cleaner!
@GetMapping("/users/{id}/profile")
suspend fun getUserProfile(@PathVariable id: String): UserProfile {
    val user = withContext(Dispatchers.IO) { userRepo.findById(id) }
        ?: throw NotFoundException("User not found")
    
    val (roles, permissions) = coroutineScope {
        val rolesDeferred = async(Dispatchers.IO) { roleRepo.getRoles(user.id) }
        val roles = rolesDeferred.await()
        val permissions = withContext(Dispatchers.IO) { permissionRepo.getPermissions(roles) }
        roles to permissions
    }
    
    return UserProfile(user, roles, permissions)
}
```

---

This research document provides the foundation for CodeLens development and should be updated as implementation progresses and new patterns are discovered.
