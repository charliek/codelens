# Ratpack Migration

!!! note "Secondary capability"

    codelens began as a tool for planning migrations off
    [Ratpack](https://ratpack.io/), and these helpers reflect that origin. They
    are a secondary capability today — the primary use case is general JVM
    codebase analysis (see the [Home](../index.md) page) — and the Ratpack-specific
    features may be phased out over time.

These commands build on the same bytecode analysis as the rest of codelens, but
add Ratpack-aware grouping and scoring. They are most useful when assessing the
size and shape of a migration.

## What it surfaces

| Area | Command group | What it answers |
|------|---------------|-----------------|
| Handlers | `codelens handlers` | Which classes are Ratpack handlers, and their shape |
| Promises | `codelens promises` | Where the async Promise API is used |
| Complexity | `codelens migration` | Per-class complexity tier and a suggested migration order |
| DI modules | `codelens modules` | Guice modules and their bindings |
| Integrations | `codelens integrations` | External service touch-points (HTTP clients, datastores, …) |
| Anti-patterns | `codelens antipatterns` | Constructs that complicate a migration |
| Routes | `codelens routes` | The route table, a route tree, and Spring `@RequestMapping` equivalents |

See the [CLI Reference](../reference/cli.md) for the full flags of each.

## Complexity tiers

`codelens migration` scores each handler/class into a tier so you can sequence
work lowest-risk first:

| Tier | Rough meaning |
|------|---------------|
| LOW | Simple, mostly synchronous handler; mechanical to port |
| MEDIUM | Blocking work, moderate Promise composition, or DI coupling |
| HIGH | Heavy parallel/async composition or many integrations |

```bash
codelens migration summary                       # tier counts across the project
codelens migration complexity com.example.Handler # one class
codelens migration order                          # suggested order, low to high
```

## Route discovery workflow

Ratpack routes are defined in handler chains rather than annotations, so route
discovery correlates the route table with handler implementations:

```bash
# 1. The route table and its tree
codelens routes list
codelens routes tree

# 2. Handlers, filtered by complexity if useful
codelens handlers list
codelens handlers list --tier HIGH

# 3. Inspect a specific handler
codelens handlers show com.example.ItemsHandler

# 4. Spring-equivalent mappings, when planning a Spring target
codelens routes spring
```

Routes assembled dynamically at runtime (loops, conditionals, computed paths)
can't always be resolved statically; treat the route table as a strong starting
inventory, not a guarantee of completeness.

## Related

- [CLI Reference](../reference/cli.md)
- [Quick Start](../getting-started/quick-start.md)
