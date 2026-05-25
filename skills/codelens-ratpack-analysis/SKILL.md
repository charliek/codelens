---
name: codelens-ratpack-analysis
description: |
  Assess a Ratpack application for migration (to Spring, Micronaut, Helidon, etc.) using
  CodeLens's general JVM primitives. Use this skill whenever the user is analyzing,
  scoping, or planning a migration OFF Ratpack — "how big is this Ratpack migration",
  "find the handlers / routes / Promise usage", "what blocks the compute thread", "what
  order should we migrate in" — or otherwise wants to understand a Ratpack codebase's
  shape. This is a worked example of doing framework-specific analysis with general
  tools: CodeLens has NO Ratpack-specific commands; every answer here is a recipe over
  `classes`, `calls`, `xref`, `deps`, `methods`, `annotations`, and `source`, with the
  Ratpack knowledge supplied by this skill's reference docs.
---

# Ratpack Migration Analysis

CodeLens deliberately ships no Ratpack-specific features. Instead it exposes a small set
of **framework-agnostic primitives** that return exhaustive bytecode facts. This skill is
the worked example of turning those facts into a Ratpack migration assessment: you supply
the **Ratpack knowledge** (which FQNs are handlers, what the Promise API looks like — see
the reference docs), CodeLens supplies the **data**, and you supply the **judgment**
(complexity, ordering, severity). Nothing here is a tool verdict; you read raw facts and
explain your reasoning.

## When to use

- Scope or plan a migration off Ratpack (to Spring MVC/WebFlux, Micronaut, etc.).
- Inventory handlers, routes, Promise/async usage, Guice DI, external integrations.
- Find constructs that complicate a migration (blocking on the compute thread, etc.).
- Propose a migration order grounded in the dependency graph.

## Prerequisites

```bash
codelens start --project /path/to/ratpack-project
```

The first start may take a few minutes while CodeLens resolves the Gradle classpath and
scans bytecode; `codelens status` reports `LOADING` → `READY`. All commands below accept
`--json` (auto-enabled when stdout is not a TTY) for piping through `jq`.

Read these reference docs for the Ratpack knowledge the recipes rely on — they hold the
FQNs, handler shapes, anti-pattern catalog, and the complexity factors you weigh by hand:

- `references/RATPACK-CONCEPTS.md` — handler types, the Promise/exec API, Chain routing, Guice.
- `references/ANTIPATTERNS.md` — what complicates a migration, how to spot it, how to fix it.
- `references/COMPLEXITY-FACTORS.md` — factors to weigh when judging effort and ordering.

## Handlers

Ratpack request handlers implement known interfaces. Find them with `implementations`:

```bash
codelens classes implementations ratpack.handling.Handler
codelens classes implementations ratpack.func.Action          # Action<Chain> route configurers
codelens classes implementations ratpack.groovy.handling.GroovyHandler
```

Inline/lambda handlers compile to `invokedynamic` and have no class of their own — find
them inside the chain that registers them (see **Routes**). Their bodies live in synthetic
`lambda$…` methods of the enclosing class, which `calls` resolves via `implMethodName`, so
you can read a lambda handler's body too. To classify a handler (see `RATPACK-CONCEPTS.md`
for the taxonomy) and judge its complexity, read its body:

```bash
codelens source show com.example.UserHandler                  # full source if available
codelens calls com.example.UserHandler --method handle        # what it invokes, from bytecode
codelens calls com.example.ApiChain --method 'lambda$execute$0'  # an inline lambda handler's body
```

`calls` is the key signal: it returns every invocation `handle` makes with constant
arguments and line numbers, so you see real `Blocking.get` / `Promise` / repository calls
rather than guessing from names.

## Routes

An `Action<Chain>` builds the route table in its `execute(Chain)` method. `calls` extracts
those route registrations directly from bytecode:

```bash
codelens calls com.example.ApiChain --method execute --json
```

In the result, each route is a call whose `ownerType` is `ratpack.handling.Chain` and
whose `methodName` is a routing method (`get`, `post`, `put`, `patch`, `delete`, `options`,
`head`, `all`, `prefix`, `path`). The **path** is the `STRING` entry in that call's
`constantArgs`; a class-literal (`CLASS`) constant arg, when present, is the handler. For
example:

```bash
# Path + method for every route the chain registers:
codelens calls com.example.ApiChain --method execute --json \
  | jq -r '.methods[].calls[]
           | select(.ownerType=="ratpack.handling.Chain")
           | "\(.methodName | ascii_upcase) " +
             ((.constantArgs[]? | select(.kind=="STRING") | .value) // "(no literal path)")'
```

`prefix(path, SomeChain.class)` nests a sub-chain: follow the class-literal constant arg
by running `calls` on that nested `Action<Chain>` and prepend the prefix path. To preview
the target shape, map each `METHOD path` to the destination framework's annotation by hand
(e.g. `GET /users/:id` → Spring `@GetMapping("/users/{id}")`).

**Inline lambda handlers** (`chain.post(ctx -> …)`) resolve too: the lambda is created by
an `"invokeDynamic": true` call site that sits *immediately before* its route call in
program order, and its `implMethodName` (e.g. `lambda$execute$0`) names the handler body.
So to read what an inline handler does, take the `implMethodName` of the indy site preceding
the route and run `calls` on it:

```bash
# Pair each inline-lambda handler with the route it backs (indy site → next Chain route):
codelens calls com.example.ApiChain --method execute --json \
  | jq -r '.methods[].calls as $c | range(0; $c|length) as $i
           | select($c[$i].invokeDynamic and ($c[$i+1].ownerType?=="ratpack.handling.Chain"))
           | "\($c[$i+1].methodName | ascii_upcase) -> \($c[$i].implMethodName)"'

# Then read the handler body:
codelens calls com.example.ApiChain --method 'lambda$execute$0'
```

> Known limit: computed (non-literal) paths show no string constant. (Lambda and
> method-reference handlers *are* resolved — via the `invokeDynamic` call site's
> `implMethodName`, as above.)

## Promise / async usage

Promise-heavy code is usually the hardest to migrate. Three complementary lenses:

```bash
# Methods whose signature returns a Promise:
codelens methods search --return-type ratpack.exec.Promise

# Every project class that touches the blocking/exec API (who, and where):
codelens xref ratpack.exec.Blocking
codelens xref ratpack.exec.Execution
codelens xref ratpack.exec.Promise

# Which domain types flow through promises? xref spans type arguments, so a type
# returned as Promise<Order> (or held as Promise<List<Order>>) is found here too:
codelens xref com.example.model.Order --kind RETURN

# Exactly what a class's method does with promises (operators, Blocking.get, fork):
codelens calls com.example.UserHandler --method handle --json \
  | jq '.methods[].calls[] | select(.ownerType | test("ratpack.exec"))'
```

`xref` of `ratpack.exec.Promise` finds every method that returns or takes a `Promise<…>`;
because references span type arguments, `xref` of a *domain* type also surfaces it where it
only ever appears wrapped in a `Promise<…>`. `xref` of `ratpack.exec.Blocking` returns each
reference as a `CALL_RECEIVER` with the method (`get`/`on`) and line number — the real
blocking call sites. Judge intensity by counting these and reading the chains in `source`.
See `RATPACK-CONCEPTS.md` for the operator/blocking taxonomy.

## Guice modules and bindings

```bash
# Modules:
codelens classes implementations com.google.inject.AbstractModule
codelens classes implementations com.google.inject.Module

# Provider methods and their constant-arg construction:
codelens methods search --annotation com.google.inject.Provides
codelens calls com.example.AppModule --method configure         # bind(...).to(...) calls

# Where a given type is bound / who depends on it:
codelens xref com.example.UserRepository
```

`calls … --method configure` surfaces the `bind`/`to`/`toInstance` invocations (with any
class-literal/string constants); `xref <type>` shows everywhere a bound type is used.

## External integrations

There is no integration detector — `xref` a library type and classify it yourself using
the catalog in `ANTIPATTERNS.md` / `RATPACK-CONCEPTS.md`. Examples:

```bash
codelens xref ratpack.http.client.HttpClient        # Ratpack HTTP client
codelens xref software.amazon.awssdk.services.dynamodb.DynamoDbClient
codelens xref org.apache.kafka.clients.producer.KafkaProducer
codelens xref javax.sql.DataSource                  # JDBC
```

Each result lists the project classes (and members/line numbers) that reference the type —
your integration inventory, grouped by `countsByKind` and `countsByPackage`.

## Anti-patterns (migration risks)

`ANTIPATTERNS.md` is the catalog (what each is, how to confirm, how to fix). Surface
candidates with `xref` / `calls`, then read context with `source` and judge:

```bash
# Blocking I/O that may sit on the compute thread:
codelens xref java.sql.Connection
codelens xref java.net.HttpURLConnection
codelens xref java.io.FileInputStream

# Thread.sleep and console logging in a class body:
codelens calls com.example.SlowHandler --json \
  | jq '.methods[].calls[]
        | select((.ownerType=="java.lang.Thread" and .methodName=="sleep")
              or (.ownerType=="java.io.PrintStream" and (.methodName=="println" or .methodName=="print")))'
```

Whether a `java.sql.*` call is an anti-pattern depends on whether it runs inside
`Blocking.get` — confirm by reading the method body (`source`) or its `calls` ordering.
The tool finds candidates; you make the call.

## Complexity and migration order

Derive the inputs from the recipes above plus the dependency graph; then rank and explain
using `COMPLEXITY-FACTORS.md` as guidance (these are factors to weigh, not a formula):

```bash
# Foundation classes — most depended-on; usually migrate first:
codelens deps foundation

# Full project dependency graph (json or dot for visualization):
codelens deps --format dot -o deps.dot

# Per-class blast radius:
codelens classes dependencies com.example.UserService --json | jq '.incoming | length'
```

A reasonable ordering: migrate high-in-degree foundation classes first, then handlers in
increasing complexity (size from `source`, async intensity from the Promise lenses,
integration count from `xref`, anti-pattern count). Show the factors behind each ranking
rather than a single opaque score.

## Suggested workflow

1. **Inventory** — `classes stats`; handlers via `implementations`; routes via `calls`.
2. **Async & risk** — Promise lenses (`methods search` / `xref` / `calls`); anti-pattern
   `xref`/`calls` + `source`.
3. **Structure** — `deps foundation` and `deps` for the graph; Guice modules.
4. **Plan** — rank by the factors in `COMPLEXITY-FACTORS.md`, foundation-first, and
   justify the order from the collected facts.

## Related skills

- `codelens-jvm-analysis` — the general primitives this skill builds on (`calls`, `xref`, `deps`, …).
- `codelens-source-lookup` — read handler/service bodies and library/JDK source.
