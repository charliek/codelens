# Spring Web FQN Catalog (the knowledge the recipes rely on)

CodeLens returns bytecode facts; this catalog is how you interpret them. FQNs are stable across
Spring 5.x/Boot 2 and Spring 6.x/Boot 3 unless noted. The one hard version boundary is the
`javax → jakarta` rename (§5).

## Contents
1. Where the annotations live
2. Meta-annotation composition (why you query the base annotation)
3. `@AliasFor` pairs (one logical slot, two attribute names)
4. Path composition
5. The `javax → jakarta` map and its traps
6. Reactive-stack FQNs
7. Stack & version discriminators

## 1. Where the annotations live

Every web-binding annotation **except `@Controller`** is in
`org.springframework.web.bind.annotation`. `@Controller` is in `org.springframework.stereotype`.
This is unchanged across Spring 5/6/7 — the `javax→jakarta` move did **not** relocate any
`org.springframework.*` annotation.

| Annotation | FQN |
|---|---|
| `@Controller` | `org.springframework.stereotype.Controller` |
| `@RestController` | `org.springframework.web.bind.annotation.RestController` |
| `@RequestMapping` | `org.springframework.web.bind.annotation.RequestMapping` |
| `@GetMapping`/`@PostMapping`/`@PutMapping`/`@DeleteMapping`/`@PatchMapping` | `org.springframework.web.bind.annotation.{Get,Post,Put,Delete,Patch}Mapping` |
| `@RequestParam`/`@PathVariable`/`@RequestHeader`/`@CookieValue`/`@MatrixVariable`/`@RequestBody`/`@RequestPart`/`@ModelAttribute` | `org.springframework.web.bind.annotation.<Name>` |
| `@ResponseBody`/`@ResponseStatus`/`@ExceptionHandler`/`@ControllerAdvice`/`@RestControllerAdvice`/`@CrossOrigin` | `org.springframework.web.bind.annotation.<Name>` |
| `@Component`/`@Service`/`@Repository`/`@Configuration` | `org.springframework.stereotype.<Name>` / `org.springframework.context.annotation.Configuration` |
| `@Bean` | `org.springframework.context.annotation.Bean` |
| `@Transactional` (Spring) | `org.springframework.transaction.annotation.Transactional` |
| `@Async`/`@Scheduled` | `org.springframework.scheduling.annotation.<Name>` |
| `@Cacheable` | `org.springframework.cache.annotation.Cacheable` |
| `@PreAuthorize`/`@PostAuthorize` | `org.springframework.security.access.prepost.<Name>` |
| `@Secured` | `org.springframework.security.access.annotation.Secured` |
| `@ConfigurationProperties` | `org.springframework.boot.context.properties.ConfigurationProperties` |
| `@Value` | `org.springframework.beans.factory.annotation.Value` |

## 2. Meta-annotation composition

CodeLens expands meta-annotations transitively into each element's annotation list, so you query
the **base** annotation and the shortcuts are matched for free:

- `@GetMapping` = `@RequestMapping(method=GET)` (same for POST/PUT/DELETE/PATCH). Plain
  `@RequestMapping` with no `method` matches all verbs. → `methods search --annotation …RequestMapping`
  returns every mapped handler; read the specific annotation type or the meta `method` attr for the verb.
- `@RestController` = `@Controller` + `@ResponseBody`; `@Controller` = `@Component`. →
  `classes list --annotation …stereotype.Controller` returns every `@RestController`;
  `…stereotype.Component` returns every stereotype-annotated bean.
- `@RestControllerAdvice` = `@ControllerAdvice` + `@ResponseBody`; `@ControllerAdvice` = `@Component`.

This also resolves **custom** composed annotations (e.g. a project's `@ApiV2Get` meta-annotated with
`@GetMapping`) as long as the annotation type is on the scanned classpath.

## 3. `@AliasFor` pairs

`@AliasFor` is a **runtime** Spring mechanism; ClassGraph does not resolve it, so in bytecode only
the attribute the author actually set is populated. Treat each pair as one logical slot and read
**both**:

| Annotation | aliased attributes |
|---|---|
| `@RequestMapping` (+ shortcuts) | `value` ↔ `path` |
| `@RequestParam`/`@PathVariable`/`@RequestHeader`/`@CookieValue`/`@MatrixVariable`/`@ModelAttribute` | `value` ↔ `name` |
| `@ResponseStatus` | `value` ↔ `code` |
| `@CrossOrigin` | `value` ↔ `origins` |
| `@ExceptionHandler` | `value` ↔ `exception` |
| `@ControllerAdvice`/`@RestControllerAdvice` | `value` ↔ `basePackages` |

## 4. Path composition

- Effective URL = class-level `@RequestMapping` path **+** method-level path (concatenated). A
  handler with no path maps to the class path.
- `value` and `path` are `String[]`; multiple values produce a cross-product of routes.
- Patterns: Ant-style (`/x/**`), URI templates (`/{id}`), placeholders (`/${p}`), SpEL (`#{...}`).
- **Trailing slash:** Spring 5 matched `/x` to `/x/` (`useTrailingSlashMatch=true`); **Spring 6
  defaults this to false** — `/x` and `/x/` are distinct unless an explicit dual mapping or a
  `UrlHandlerFilter` exists. Relevant when modeling the URL space on a Boot 3 target.

## 5. The `javax → jakarta` map and its traps

Spring 6 / Boot 3 (Java 17+) baselines on Jakarta EE 9+, renaming EE spec packages `javax.* →
jakarta.*`. **Key analysis types must be queried under both namespaces** depending on the target's
version (detect via §7).

| Spec | Spring 5 / Boot 2 | Spring 6 / Boot 3 |
|---|---|---|
| Servlet | `javax.servlet.*` (e.g. `javax.servlet.http.HttpServletRequest`) | `jakarta.servlet.*` |
| Bean Validation | `javax.validation.*` (`@Valid`, `constraints.*`) | `jakarta.validation.*` |
| Persistence (JPA) | `javax.persistence.*` (`@Entity`, `@Table`, …) | `jakarta.persistence.*` |
| Common annotations (JSR-250) | `javax.annotation.{PostConstruct,PreDestroy,Resource}` | `jakarta.annotation.*` |
| DI | `javax.inject.Inject` | `jakarta.inject.Inject` |
| Transaction (JTA) | `javax.transaction.Transactional` | `jakarta.transaction.Transactional` |
| JMS / Mail / WS / etc. | `javax.jms`, `javax.mail`, `javax.ws.rs`, … | `jakarta.*` |

**Traps:**
- **JDBC stays `javax`/`java`.** `javax.sql.DataSource` and all `java.sql.*` are Java SE, **not**
  Jakarta EE — they do **not** migrate. Key blocking-JDBC detection on these regardless of version.
- **JSR-305 nullability stays `javax`.** `javax.annotation.Nonnull`/`Nullable` are JSR-305 (a
  different spec) — they have **no** `jakarta` equivalent. Don't rewrite them or use them for
  version inference.
- **Spring's own `@Transactional`** (`org.springframework.transaction.annotation.Transactional`) is
  distinct from the JTA `*.transaction.Transactional` and is **unchanged** — it's the one you'll
  usually find in service code.
- Other Java SE `javax.*` (`javax.net`, `javax.crypto`, `javax.xml.parsers/transform/stream/xpath`,
  `javax.transaction.xa`) stay `javax`. Only Jakarta EE *spec* packages moved.

## 6. Reactive-stack FQNs

| Concept | FQN |
|---|---|
| Reactor mono/flux | `reactor.core.publisher.Mono`, `reactor.core.publisher.Flux` |
| Reactive Streams | `org.reactivestreams.Publisher` |
| Functional routing | `org.springframework.web.reactive.function.server.{RouterFunction,RouterFunctions,HandlerFunction,ServerRequest,ServerResponse,RequestPredicates}` |
| Reactive HTTP client | `org.springframework.web.reactive.function.client.WebClient` |
| Blocking HTTP client | `org.springframework.web.client.RestTemplate` (and `RestClient`, synchronous fluent) |
| Reactive Spring Data | `org.springframework.data.repository.reactive.{ReactiveCrudRepository,ReactiveSortingRepository}`, `org.springframework.data.r2dbc.repository.R2dbcRepository`, `org.springframework.data.mongodb.repository.ReactiveMongoRepository` |
| Schedulers | `reactor.core.scheduler.Schedulers` (`boundedElastic`, `parallel`, `single`) |

## 7. Stack & version discriminators

Annotations are byte-identical between MVC and WebFlux; classify by infrastructure on the classpath:

| Signal | Means |
|---|---|
| `org.springframework.web.reactive.DispatcherHandler` present | WebFlux on classpath |
| `org.springframework.web.servlet.DispatcherServlet` present | MVC on classpath |
| both present | hybrid — classify each controller by its return types |
| reactive `…web.reactive.result.method.annotation.RequestMappingHandlerMapping` | WebFlux annotated controllers |
| `org.springframework.web.reactive.function.server.support.RouterFunctionMapping` | functional WebFlux routes wired |
| `jakarta.*` EE types present | Spring 6 / Boot 3 / Java 17+ |
| `javax.*` EE types present | Spring 5 / Boot 2 |

`RouterFunction` also exists in **MVC.fn** under `org.springframework.web.servlet.function.*` (handler
returns a plain `ServerResponse`, not `Mono<ServerResponse>`). The package — `web.reactive.function.server`
vs `web.servlet.function` — is the discriminator.
