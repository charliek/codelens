# Spring WebFlux reference

WebFlux is the **reactive-stack** web model: a non-blocking `DispatcherHandler` runs on a small,
fixed pool of event-loop threads (Reactor Netty `reactor-http-nio-*`, ~one per core). The core
assumption is that **handlers never block** — see `BLOCKING-IN-REACTIVE.md`. WebFlux exposes two
endpoint models.

## Model 1 — annotated controllers

Identical annotations to MVC (`@RestController`, `@RequestMapping`, `@GetMapping`, …), but handler
methods return reactive publishers:

- `reactor.core.publisher.Mono<T>` — 0..1 element.
- `reactor.core.publisher.Flux<T>` — 0..N elements.
- `org.reactivestreams.Publisher<T>` and adapted types (RxJava, Kotlin `Flow`, `CompletableFuture`).

WebFlux also accepts **reactive request bodies** (e.g. `@RequestBody Mono<Dto>`), which MVC does not.

Classify a handler as reactive when its return (or `@RequestBody` parameter) type resolves to
`Mono`/`Flux`/`Publisher`. This is **strong but not decisive** — MVC controllers can also return
`Mono`/`Flux`, and a WebFlux handler may return plain `String`/`ResponseEntity`. Break ties with the
classpath stack discriminator (`SPRING-WEB-FQN.md` §7).

## Model 2 — functional endpoints (WebFlux.fn)

Routes are declared in a `@Bean` returning
`org.springframework.web.reactive.function.server.RouterFunction<ServerResponse>`, built with the
`RouterFunctions.route()` DSL and `RequestPredicates`; handlers have shape
`Mono<ServerResponse> handle(ServerRequest)`. These carry **no annotations**, so the routes are
recovered from the bean body — the same technique as a Ratpack chain:

```bash
codelens methods search --return-type org.springframework.web.reactive.function.server.RouterFunction --json
codelens calls com.example.web.CatalogRouter --method catalogRoutes --json
```

Two declaration styles, with different extractability:

- **Classic** `RouterFunctions.route(RequestPredicates.GET("/p"), handler::get).andRoute(...)` —
  the path `"/p"` is the **sole constant arg** of the `GET` predicate, so it **is** captured; pair
  each `GET`/`POST`/… predicate with the adjacent handler reference (an `invokeDynamic` whose
  `implMethodName` names the handler method).
- **Fluent builder** `route().GET("/p", accept(...), handler::get).build()` — the path LDC is **not**
  adjacent to the routing call, so it is **not** captured in `constantArgs`. For builder-style
  routers, read `source` to get the paths; `calls` still shows the route structure + handler refs.

> Discriminator: the **same** `RouterFunction` DSL exists in **MVC.fn** under
> `org.springframework.web.servlet.function.*` (handler returns plain `ServerResponse`). Confirm the
> package is `web.reactive.function.server` before treating routes as reactive.

## Reactive data & clients

- Reactive repositories: `ReactiveCrudRepository`, `R2dbcRepository`, `ReactiveMongoRepository`
  (return `Mono`/`Flux`). Find with `classes implementations org.springframework.data.repository.reactive.ReactiveCrudRepository`.
- HTTP: reactive `WebClient` (`…web.reactive.function.client.WebClient`) vs blocking `RestTemplate`
  (`…web.client.RestTemplate`). `RestTemplate` on a reactive path is an antipattern.

## Schedulers & threading

`reactor.core.scheduler.Schedulers`:

- `boundedElastic()` — the **only** built-in place to offload blocking work (capped ~10× cores,
  100k queued tasks/thread, 60s idle eviction; virtual-thread-backed on JDK 21+ when enabled).
- `parallel()` / `single()` — CPU-bound, `NonBlocking`-marked; **do not** run blocking work here.

`subscribeOn(scheduler)` moves the **source/upstream** (placement-independent; only the one nearest
the source wins). `publishOn(scheduler)` moves **downstream** operators after its position
(placement matters). To offload a blocking source: `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`.

## Boot 2 vs Boot 3 (reactive)

WebFlux doesn't depend on the Servlet API, so the `javax→jakarta` change mainly bites via Bean
Validation on request bodies and shared Jakarta libs. Tracing/observability moved from Spring Cloud
Sleuth (Boot 2) to Micrometer Observation/Tracing (Boot 3); reactive context propagation of
ThreadLocals needs `Hooks.enableAutomaticContextPropagation()` + `io.micrometer:context-propagation`.
See `BLOCKING-IN-REACTIVE.md` for the migration sharp edges.
