---
name: codelens-spring-web-analysis
description: |
  Use this skill to examine, inventory, search, or navigate an existing Spring web application —
  Spring MVC, Spring WebFlux, or a hybrid of both. It covers: finding controllers and listing
  every endpoint with its HTTP verb / path / handler / return type; resolving which controller
  handles a given route; tracing a request from endpoint → service → repository; finding what
  injects a given bean (blast radius before a refactor); classifying reactive (`Mono`/`Flux`)
  vs blocking handlers; finding blocking calls (`.block()`, JDBC, `RestTemplate`, `Thread.sleep`)
  sitting inside reactive code; mapping `@Transactional` boundaries (incl. proxy self-invocation
  traps), `@ControllerAdvice`/`@ExceptionHandler`, `@PreAuthorize` + `SecurityFilterChain` rules,
  `@ConfigurationProperties`/`@Value`, and MapStruct DTO↔entity mappers. Spring spreads routing
  across class- and method-level annotations, hides WebFlux functional routes and security rules
  inside builder lambdas, and uses byte-identical annotations for MVC and WebFlux — so reach for
  this skill even when the request sounds like a simple "find the controller for `GET /orders`,"
  "list the endpoints," "is this handler reactive," or a grep-style lookup. Also covers scoping an
  MVC↔WebFlux migration. Skip it for writing or scaffolding new Spring code, conceptual
  "how does Spring work" questions, and analyzing non-Spring apps. A worked example of
  framework-specific analysis built entirely on CodeLens's general primitives — `classes`,
  `methods`, `calls`, `xref`, `deps`, `annotations`, `source`.
---

# Spring Web Analysis (MVC + WebFlux)

CodeLens ships no Spring-specific features. It exposes framework-agnostic **primitives** that
return exhaustive bytecode facts; this skill supplies the **Spring knowledge** (which FQNs are
controllers, how routes compose, what blocking-in-reactive looks like — see the reference docs),
and you supply the **judgment**. Nothing here is a tool verdict: you read raw facts and reason.

Spring is, helpfully, mostly **declarative metadata**, and CodeLens reads it directly. Two facts
make this skill fast:

- **CodeLens expands meta-annotations transitively.** `@GetMapping` *is*
  `@RequestMapping(method=GET)`, and `@RestController` *is* `@Controller` + `@ResponseBody`. So
  `methods search --annotation …RequestMapping` finds **every** `@GetMapping`/`@PostMapping`/…
  handler regardless of verb, and `classes list --annotation …stereotype.Controller` finds every
  `@RestController`. You query the base annotation; the shortcuts are matched for free.
- **Annotation attribute values are captured as typed values.** Each attribute is a typed node
  (`{kind, value|items|enumType|…}`): route paths (`value`/`path`) come back as an `ARRAY` of
  `STRING` items and the verb (`method`) as an `ARRAY` of `ENUM` items, so you read a path as
  `.parameters.value.items[0].value` and the verb as `.parameters.method.items[0].value` — no
  bracket-string parsing.

## When to use

- List/inventory endpoints; resolve "what handles `GET /x`"; map the API surface.
- Trace a request through the layers; find a bean's injection blast radius.
- Classify reactive vs blocking; find blocking calls inside reactive handlers.
- Map cross-cutting concerns: security, transactions, advice, config, DTO mapping.
- Scope an MVC↔WebFlux migration.

## Prerequisites

**Build the project first** — CodeLens scans compiled bytecode (`build/classes`), so an uncompiled
project scans to zero classes and every query is empty: `cd /path/to/project && ./gradlew build -x test`
(or `mvn -DskipTests compile`). Then:

```bash
codelens start --project /path/to/project
```

The first start may take minutes (Gradle classpath + scan); `codelens status` goes `LOADING` →
`READY`. Sanity-check with `codelens classes stats --json` — a `projectClassCount` of `0` (also
flagged by a `warning:` from `start`) means rebuild, then `codelens refresh`. In a terminal,
commands print tables; **pass `--json` for the stable machine-readable payload** (auto-selected
when piped). Recipes below pass `--json` so they parse with `jq`.

Read the reference docs for the Spring knowledge the recipes rely on:

- `references/SPRING-WEB-FQN.md` — the verified FQN catalog: annotation package, meta-annotation
  composition, `@AliasFor` pairs, the `javax→jakarta` dual-key map and its traps, reactive-type
  FQNs, and the stack/version discriminators.
- `references/MVC.md` — the MVC request model (REST vs view, param binding, async MVC).
- `references/WEBFLUX.md` — annotated vs functional endpoints, Reactor types, schedulers,
  reactive data, and the WebFlux.fn-vs-MVC.fn distinction.
- `references/BLOCKING-IN-REACTIVE.md` — the blocking-on-a-reactive-thread catalog and how to
  surface each from bytecode.

## Step 1 — Classify the stack (and version) first

MVC and WebFlux use **byte-identical annotations**, and a reactive return type is suggestive but
not decisive (MVC handlers may return `Mono`; WebFlux handlers may return `String`). The reliable
signal is **which dispatch infrastructure is on the classpath**:

```bash
# WebFlux present?  -> org.springframework.web.reactive.DispatcherHandler
codelens classes list --include-libraries --name '*DispatcherHandler' --json | jq -r '.classes[].fqn'
# MVC present?      -> org.springframework.web.servlet.DispatcherServlet
codelens classes list --include-libraries --name '*DispatcherServlet' --json | jq -r '.classes[].fqn'
```

Both present ⇒ a **hybrid** app (analyze each controller by its return types). Version: a
`jakarta.*` EE surface ⇒ Spring 6 / Boot 3 (Java 17+); `javax.*` ⇒ Spring 5 / Boot 2 — this
changes which FQNs you `xref` (see SPRING-WEB-FQN.md):

```bash
codelens xref jakarta.persistence.Entity --json | jq '.countsByKind'   # non-empty ⇒ Boot 3
```

## Step 2 — Endpoint inventory

**Annotated controllers** (MVC and WebFlux). Run the bundled helper, which joins each
controller's class-level `@RequestMapping` base path with every mapped method's path + verb and
flags reactive return types:

```bash
scripts/endpoints.sh /path/to/project
```

It is built on these primitives, which you can also run directly:

```bash
# every controller (meta-annotation match catches @RestController):
codelens classes list --annotation org.springframework.stereotype.Controller --json | jq -r '.classes[].fqn'

# every mapped handler method + its path/verb (meta @RequestMapping catches all the shortcuts):
codelens methods search --annotation org.springframework.web.bind.annotation.RequestMapping --json

# the class-level base path to prepend (value/path are @AliasFor aliases, each an ARRAY of STRING):
codelens classes show com.example.web.OrderController --json \
  | jq -r '.classInfo.annotations[] | select(.type|endswith("RequestMapping"))
           | (.parameters.value.items[0].value // .parameters.path.items[0].value // "")'
```

The path is the first `STRING` item of the annotation's `value` (or `path` — they are `@AliasFor`
aliases, so check both); a no-path `@RequestMapping` is an empty array (`items: []`). The verb is
the specific annotation type (`GetMapping`→GET) or the first `ENUM` item of the meta
`RequestMapping`'s `method` array (`.parameters.method.items[0].value` → `"GET"`). **Reverse
lookup** ("what handles `GET /orders/{id}`") is the inventory filtered to that verb+path.

**WebFlux functional routes** (`RouterFunction` beans) carry **no annotations** — recover them by
reading the bean body, exactly like a Ratpack chain:

```bash
# find the router beans:
codelens methods search --return-type org.springframework.web.reactive.function.server.RouterFunction --json
# read the routes: pair each RequestPredicates.GET("/path") with the adjacent handler ref:
codelens calls com.example.web.CatalogRouter --method catalogRoutes --json \
  | jq -r '.methods[].calls[] | select((.methodName|test("^(GET|POST|PUT|DELETE|PATCH)$")) or (.invokeDynamic // false))
           | "\(.methodName)\t\(.implMethodName // "")\t\([.constantArgs[]?|select(.kind=="STRING")|.value]|join(","))"'
```

> Limit: the **fluent builder** form `route().GET("/p", predicate, handler)` does not expose the
> path constant (the path LDC is not adjacent to the routing call). The classic
> `route(GET("/p"), handler)` form does. For builder-style routers, read `source`.

## Step 3 — Trace requests & blast radius

```bash
# what a handler invokes (one hop); follow service→repo by repeating on the callee:
codelens calls com.example.web.OrderController --method getOrder --json
# when a hop lands on an interface (e.g. a @Service interface), resolve the impl:
codelens classes implementations com.example.service.OrderService
# who injects / references a bean (blast radius before a change):
codelens xref com.example.service.OrderService            # all references, grouped by kind
codelens classes dependencies com.example.service.OrderService --json | jq '.incoming'
# the project's most depended-on types:
codelens deps foundation
```

`calls` is one hop today; chain it down the layers, resolving interface hops via
`classes implementations`. (Issue #42 would make this a single `trace` command.)

## Step 4 — Reactive correctness (blocking-in-reactive)

The cardinal WebFlux sin is blocking the event loop. Find methods that return `Mono`/`Flux`, then
inspect their call-sites for blocking surfaces — see `BLOCKING-IN-REACTIVE.md` for the full catalog
and why each blocks. **Two easy-to-miss cases (read `BLOCKING-IN-REACTIVE.md` §0):** the blocking
call is usually a hop or two down in a `@Service`/`@Repository` — trace transitively (chain `calls`,
resolving interface→impl via `classes implementations`), don't stop at the reactive wrapper; and a
blocking call passed as an *argument* to a reactive factory (`Flux.fromIterable(repo.findAll())`,
`Mono.just(svc.load())`) runs **eagerly at assembly time on the event-loop thread**, so inspect the
arguments of `Mono`/`Flux` factory calls, not just the operator chain.

```bash
# the reactive surface:
codelens methods search --return-type reactor.core.publisher.Mono --json
codelens xref reactor.core.publisher.Flux

# DIRECT blocking call-sites across ALL reactive handlers in a class, in ONE query (#44): scope
# `calls` to the enclosing Mono/Flux methods (--in-methods-returning) — no manual methods×calls
# intersection. Add --method to drill into one handler; --in-methods-annotated <ann> is the
# annotation-scoped sibling (e.g. only @GetMapping handlers).
codelens calls com.example.web.ReactiveController --in-methods-returning reactor.core.publisher.Mono --json \
  | jq '.methods[].calls[] | select(
        (.ownerType=="reactor.core.publisher.Mono" and (.methodName|test("^block")))
        or (.ownerType=="java.lang.Thread" and .methodName=="sleep")
        or (.ownerType|test("^(java|javax)\\.sql\\."))
        or (.ownerType=="org.springframework.web.client.RestTemplate"))'
```

`--in-methods-returning`/`--in-methods-annotated` scope to **direct** call-sites in the matching
handler bodies (by the enclosing method's declared signature) — they do **not** reach the
eager-assembly arguments or the `lambda$…`/transitive callees described above, so they
**complement**, not replace, the transitive trace. Note: `java.sql.*` / `javax.sql.*` stay
`javax`/`java` across the Boot 2→3 boundary (not part of the Jakarta rename), so JDBC detection is
version-independent — see SPRING-WEB-FQN.md.

## Step 5 — Cross-cutting concerns

`annotations usages <fqn> --scope method|field|param|class|all` (#43) returns every site
carrying the annotation **with its typed attribute values inline** — so a single call answers
"where is X used, and with what config." `--scope method` also covers constructors (each row has a
`target`; filter with `select(.target=="METHOD")` if you want methods only). Matching is meta-expanded.

```bash
# Transactions: every @Transactional method (--scope method also surfaces class-level @Transactional):
codelens annotations usages org.springframework.transaction.annotation.Transactional --scope method --json
#   then check for SELF-INVOCATION (a @Transactional method called via `this` bypasses the proxy):
codelens calls com.example.service.OrderService --json   # look for in-class calls to the @Transactional method

# Security: every @PreAuthorize method WITH its SpEL expression, in one call:
codelens annotations usages org.springframework.security.access.prepost.PreAuthorize --scope method --json \
  | jq -r '.usages[] | "\(.classSimpleName).\(.method)\t\(.annotation.parameters.value.value)"'
# plus the central filter chain (rules live in a synthetic lambda$filterChain$N — discover it first):
codelens calls com.example.config.SecurityConfig --json | jq -r '.methods[].methodName'   # lists the lambdas
codelens calls com.example.config.SecurityConfig --method 'lambda$filterChain$1' --json \
  | jq -r '.methods[].calls[] | "\(.methodName)\t\([.constantArgs[]?|select(.kind=="STRING")|.value]|join(","))"'

# Exception handling: every @ExceptionHandler method AND the exception type(s) it maps (a CLASS array):
codelens annotations usages org.springframework.web.bind.annotation.ExceptionHandler --scope method --json \
  | jq -r '.usages[] | "\(.classSimpleName).\(.method)\t\([.annotation.parameters.value.items[]?.value]|join(","))"'

# Config binding: @ConfigurationProperties classes (prefix) + @Value fields with their property key, inline:
codelens annotations usages org.springframework.boot.context.properties.ConfigurationProperties --scope class --json
codelens annotations usages org.springframework.beans.factory.annotation.Value --scope field --json \
  | jq -r '.usages[] | "\(.classSimpleName).\(.field)\t\(.annotation.parameters.value.value)"'

# DTO mapping: MapStruct @Mapper interfaces; then `source`/`calls` on the *Impl for field mapping:
codelens annotations usages org.mapstruct.Mapper --scope class
```

**Boundary:** CodeLens sees *bytecode*. Effective property values live in `application.yml`/
`.properties` and security config files — read those directly. `SecurityFilterChain`
`requestMatchers(...)` paths are inside a builder lambda (`lambda$filterChain$N`); read that
synthetic method, as above.

## Step 6 — MVC↔WebFlux migration (secondary)

Census the blocking vs reactive split (`xref reactor.core.publisher.Mono` vs `xref javax.sql.DataSource`),
order by `deps foundation`, and use `BLOCKING-IN-REACTIVE.md` to size the hard parts
(`ThreadLocal`-bound security/MDC/transactions, JDBC/JPA → R2DBC). Show the factors, not a score.

## Suggested workflow

1. **Classify** — stack (reactive/servlet/hybrid) + version (jakarta/javax).
2. **Inventory** — `scripts/endpoints.sh`; functional routes via `calls`.
3. **Trace / blast radius** — `calls` + `classes implementations`; `xref`/`deps`.
4. **Correctness** — reactive surface + blocking-in-reactive `calls`.
5. **Cross-cutting** — security, transactions, advice, config, mapping.

## Related skills

- `codelens-jvm-analysis` — the general primitives this builds on (`calls`/`xref`/`deps`/…).
- `codelens-source-lookup` — read controller/service bodies and library/JDK source.
- `codelens-ratpack-analysis` — the sibling worked example (lambda-hidden routes/Promises).
