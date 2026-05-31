# Blocking-in-reactive: the antipattern catalog

In WebFlux a tiny fixed pool of event-loop threads services every request; **one blocking call on
one of those threads stalls every connection multiplexed on it** ("reactor meltdown"). This is the
top correctness concern in WebFlux code. CodeLens finds the candidate call-sites; you confirm by
reading context (`source`/`calls`) and judging whether the method is on a reactive path.

The general recipe: find methods returning `Mono`/`Flux` (the reactive entry points), then inspect
their call-sites (`calls`) for the blocking surfaces below. Remember lambdas: a blocking call inside
a `flatMap`/`map`/`fromCallable` lambda lives in a synthetic `lambda$…` method — resolve it via the
`invokeDynamic` site's `implMethodName` and `calls` that method too.

## Contents
1. `.block()` / `blockFirst()` / `blockLast()` / `blockOptional()`
2. Blocking JDBC / JPA
3. `RestTemplate`
4. `Thread.sleep`
5. Synchronous file I/O
6. `Future.get()` / `CompletableFuture.join()`
7. Missing `subscribeOn(boundedElastic())`
8. The two runtime guards (Reactor + BlockHound)
9. MVC → WebFlux migration: the hard parts

## 1. `block*` terminal operators

`reactor.core.publisher.Mono.block` / `blockOptional`; `Flux.blockFirst` / `blockLast`. They
subscribe and synchronously wait. On a `reactor.core.scheduler.NonBlocking` thread (event loop,
`parallel`, `single`) Reactor throws `IllegalStateException: block()/... not supported in thread …`.

Detect: in a method returning `Mono`/`Flux`, a call with `ownerType` `reactor.core.publisher.Mono`/
`Flux` and `methodName` matching `^block`. **Fix:** don't unwrap — compose with `map`/`flatMap` and
return the publisher to the framework.

## 2. Blocking JDBC / JPA

`java.sql.{Connection,Statement,PreparedStatement,ResultSet}` (`execute*`, `next`),
`jakarta.persistence.EntityManager`, Spring Data `org.springframework.data.jpa.repository.JpaRepository`.
JDBC bottoms out in a blocking socket read; JPA's `EntityManager` is synchronous **and**
`ThreadLocal`-bound — fundamentally incompatible with thread-hopping pipelines.

Detect: call-sites whose `ownerType` starts `java.sql.`/`javax.sql.` (these stay `javax`/`java` —
**not** renamed to jakarta), or a `JpaRepository` method, inside a reactive method. **Fix:** R2DBC
(`R2dbcRepository`/`DatabaseClient`) for fully-reactive SQL; or keep JPA on MVC; or, as a stopgap,
`Mono.fromCallable(repo::find).subscribeOn(Schedulers.boundedElastic())`.

## 3. `RestTemplate`

`org.springframework.web.client.RestTemplate` (`getForObject`, `exchange`, …) blocks for the whole
remote round-trip. **Fix:** `org.springframework.web.reactive.function.client.WebClient`.

## 4. `Thread.sleep`

`java.lang.Thread.sleep(...)` parks the loop thread. **Fix:** `Mono.delay(Duration)` /
`Flux.interval` / `delayElements` (non-blocking, on the `parallel` scheduler).

## 5. Synchronous file I/O

`java.io.{FileInputStream,FileOutputStream,RandomAccessFile}`, blocking `FileChannel`. **Fix:**
`org.springframework.core.io.buffer.DataBufferUtils.read(...)` (→ `Flux<DataBuffer>`), or offload to
`boundedElastic`.

## 6. `Future.get()` / `CompletableFuture.join()`

`java.util.concurrent.Future.get`, `CompletableFuture.get`/`join` block (via `Unsafe.park`).
**Fix:** `Mono.fromFuture(CompletableFuture)` / `Mono.fromCompletionStage` — never `.get()`/`.join()`
in a pipeline.

## 7. Missing `subscribeOn(boundedElastic())`

When a blocking call is genuinely unavoidable, the correct shape is
`Mono.fromCallable(() -> blockingCall()).subscribeOn(Schedulers.boundedElastic())`. A blocking call
**not** wrapped this way (or wrapped with `publishOn` placed *below* it, or on `parallel`/`single`)
still runs on the event loop. Detect: a blocking surface (§§2–6) on a reactive path with no
intervening `subscribeOn`/`publishOn(boundedElastic())`. `Schedulers.parallel()`/`single()` are
`NonBlocking` and are **not** valid offload targets.

## 8. The two runtime guards (for the team's test suite, not CodeLens)

- **Reactor's own guard** fires only for `block*`/`toIterable`/`toStream` on a `NonBlocking` thread
  (`Schedulers.isInNonBlockingThread()`); it does **not** catch JDBC/sleep/file I/O.
- **BlockHound** (`io.projectreactor.tools:blockhound`) is a Java agent that instruments low-level
  JVM methods (`Thread.sleep`, socket reads, `Unsafe.park`, file I/O) and throws
  `BlockingOperationError` from a non-blocking thread. Recommend it in the project's tests
  (`blockhound-junit-platform`) to convert invisible production stalls into deterministic failures.

Recommend these as runtime confirmation of what static analysis surfaces.

## 9. MVC → WebFlux migration: the hard parts

All `ThreadLocal`-bound, because reactive chains are thread-agnostic:

- **Security**: `SecurityContextHolder` (ThreadLocal) → `ReactiveSecurityContextHolder` (stored in
  the Reactor `Context`); `@EnableReactiveMethodSecurity`.
- **MDC logging**: not propagated by default; use `doOnEach`, or a `ThreadLocalAccessor` +
  `Hooks.enableAutomaticContextPropagation()`.
- **Transactions**: imperative `@Transactional` (ThreadLocal-bound connection) → `ReactiveTransactionManager`
  (`R2dbcTransactionManager`) or `TransactionalOperator`. JPA cannot be made reactive.
- **Servlet API**: `Filter`/`HandlerInterceptor` → `org.springframework.web.server.WebFilter`;
  `HttpSession` → `WebSession`; `MultipartFile` → `Flux<Part>`.
- **Decision**: for JPA-heavy, non-streaming modules, **MVC on Java 21+ virtual threads** is often
  the pragmatic alternative to WebFlux (keeps imperative JPA + correct `@Transactional`). Reserve
  WebFlux for streaming/backpressure/fan-out. Never mix a blocking and non-blocking boundary on one
  request path.

Use this section to size migration effort: census the blocking surfaces (§§1–6) reachable from
reactive entry points, weight by the `ThreadLocal` concerns above, and order by `deps foundation`.
