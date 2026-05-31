# Spring MVC reference

Spring MVC is the **servlet-stack** web model: `DispatcherServlet` dispatches to `@Controller`
beans, handlers run on a large thread-per-request pool (Tomcat `server.tomcat.threads.max`
defaults to 200), and blocking I/O is expected and fine. Contrast with WebFlux (`WEBFLUX.md`).

## Controllers

- `@Controller` — a stereotype bean whose handler methods return **view names** by default (a
  template name resolved by a `ViewResolver`).
- `@RestController` = `@Controller` + `@ResponseBody` — handler return values are serialized to the
  response body (JSON/XML), not treated as view names.
- `@ResponseBody` on a method/class forces body serialization within a plain `@Controller`.

Find them: `classes list --annotation org.springframework.stereotype.Controller` (meta-matches
`@RestController`). Distinguish REST from view controllers by the presence of `@ResponseBody`/
`@RestController` (in the class annotations) vs a `String`/`ModelAndView` return type.

## Request mapping & binding

Handlers are `@RequestMapping` (or a verb shortcut). Parameters bind via:

| Annotation | Binds | Notes |
|---|---|---|
| `@PathVariable` | URI template var (`/{id}`) | `required=true` by default |
| `@RequestParam` | query/form param | `required=true`; any `defaultValue` implies `required=false` |
| `@RequestBody` | deserialized body | `required=true` |
| `@RequestHeader`/`@CookieValue` | header/cookie | `defaultValue` ⇒ optional |
| `@ModelAttribute` | bound command object | also marks model-populating methods |
| `@Valid`/`@Validated` | triggers Bean Validation on the bound object | constraints in `jakarta.validation.constraints.*` (Boot 3) / `javax.validation.*` (Boot 2) |

`@RequestParam`/`@PathVariable`/etc. carry their bound name in `value`/`name` (aliases — read both).
The param annotations appear under each `ParameterInfo.annotations` in `classes show`/`methods search`.

## Async MVC (still servlet-stack, not WebFlux)

A blocking MVC handler can return an async wrapper to free the servlet thread while a result is
produced elsewhere — this is **not** WebFlux:

- `org.springframework.web.context.request.async.DeferredResult<T>`
- `java.util.concurrent.Callable<T>`
- `org.springframework.web.servlet.mvc.method.annotation.SseEmitter` / `ResponseBodyEmitter`
- `java.util.concurrent.CompletableFuture<T>`

A handler may also return Reactor `Mono`/`Flux` from an MVC controller (adapted by
`ReactiveAdapterRegistry`) — so a reactive return type alone does **not** prove WebFlux. Use the
stack discriminator in `SPRING-WEB-FQN.md` §7.

## Exception handling

- `@ExceptionHandler` method on a controller — local handling.
- `@ControllerAdvice` / `@RestControllerAdvice` class — global handling across controllers; selectors
  (`basePackages`, `assignableTypes`, `annotations`) narrow scope, OR-combined; none ⇒ global.
- `@ResponseStatus` maps a handler/exception to an HTTP status (`value`↔`code` alias).
- `org.springframework.web.server.ResponseStatusException` thrown directly carries a status.

Surface: `annotations usages …ExceptionHandler --scope method` returns each handler method **and** the
exception type(s) it maps inline — the `value` attribute is a CLASS array (fall back to the parameter
type when `value` is empty). Pair with `annotations usages …RestControllerAdvice --scope class` for the
advice classes.

## Cross-cutting

`@Transactional` (Spring's, `org.springframework.transaction.annotation`) on service methods;
`@PreAuthorize`/`@Secured` for method security; `SecurityFilterChain` for URL rules. See the SKILL.md
recipes and `SPRING-WEB-FQN.md`. Watch the **self-invocation** trap: a `@Transactional` (or
`@Async`/`@Cacheable`) method called via `this` from a sibling method bypasses the proxy, so the
advice doesn't apply — visible only by joining the annotation inventory with the call graph.
