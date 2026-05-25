# Complexity Factors Reference

This is guidance for judging migration complexity yourself. CodeLens does not compute a
complexity score — it returns raw facts, and you weigh the factors below with judgment. The
"impact" columns are qualitative reference points, not a formula; treat the listed weights as
relative importance to consider, and for each measurable factor use the noted general command
to pull the raw input.

## How to weigh it

Gather the inputs per class, then reason about overall effort from the mix of factors below —
deep async chains and anti-patterns dominate; raw size barely moves it. There is no single
number to read off; explain the factors that drove your assessment.

## Factors

### Lines of Code

**Relative weight:** Low
**Description:** Raw size of the handler implementation.
**How to measure:** `codelens source show <fqn>` (read the body length).

| LOC | Impact |
|-----|--------|
| < 50 | Minimal |
| 50-150 | Low |
| 150-300 | Medium |
| > 300 | High |

**Why it matters:** Larger classes take longer to understand and migrate.

---

### Handler Type

**Relative weight:** Low-Medium
**Description:** Type of Ratpack handler.
**How to measure:** classify from `implementations` + `source` (see `RATPACK-CONCEPTS.md`).

| Type | Impact |
|------|--------|
| HANDLER | Low |
| CHAIN_ACTION | Medium |
| INLINE_HANDLER | Low |
| GROOVY_HANDLER | Medium-High |

**Why it matters:** Chain actions require understanding routing structure. Groovy handlers may need language translation.

---

### Injected Dependencies

**Relative weight:** Medium
**Description:** Number of @Inject dependencies.
**How to measure:** read the constructor/fields via `codelens source show <fqn>`, or
`codelens methods search --annotation javax.inject.Inject` across the project.

| Count | Impact |
|-------|--------|
| 0-2 | Low |
| 3-5 | Medium |
| 6-10 | High |
| > 10 | Very High |

**Why it matters:** Each dependency must be understood and potentially migrated.

---

### Promise Operations

**Relative weight:** Medium-High
**Description:** Number and type of Promise operations.
**How to measure:** `codelens xref ratpack.exec.Blocking` / `xref ratpack.exec.Promise`, and
`codelens calls <fqn> --method <m>` filtered to `ratpack.exec` for the exact operators.

| Aspect | Relative weight |
|--------|--------|
| Total operations | Medium |
| Blocking.get usage | Medium |
| flatMap chains | High |
| ParallelBatch | High |

**Why it matters:** Promise patterns must be translated to target framework's async model.

---

### Promise Chain Depth

**Relative weight:** High
**Description:** Maximum nesting level of Promise operations.
**How to measure:** read the operator chain in `codelens source show <fqn>` (the `calls` list
shows the operators, but nesting depth is clearest from the source).

| Depth | Impact |
|-------|--------|
| 1-2 | Low |
| 3-4 | Medium |
| 5-7 | High |
| > 7 | Very High |

**Why it matters:** Deep chains are hard to understand and translate.

---

### External Integrations

**Relative weight:** Medium
**Description:** Number of external service integrations.
**How to measure:** `codelens xref <client-type>` per integration (see `RATPACK-CONCEPTS.md`
for the catalog of types to check).

| Type | Relative weight |
|------|--------|
| HTTP Client | Medium |
| Database | Medium-High |
| Message Queue | Medium |
| Cache | Low |
| gRPC | Medium |

**Why it matters:** Each integration may have different migration patterns.

---

### Anti-Patterns

**Relative weight:** Medium-High
**Description:** Number and severity of anti-patterns you've confirmed.
**How to measure:** the recipes in `ANTIPATTERNS.md` (`xref` / `calls` + `source`).

| Severity | Relative weight |
|----------|--------|
| INFO | Very Low |
| WARNING | Low |
| ERROR | Medium |
| CRITICAL | High |

**Why it matters:** Anti-patterns indicate code that needs fixing, adding migration effort.

---

### Dependency Count

**Relative weight:** Low-Medium
**Description:** Number of classes this handler depends on.
**How to measure:** `codelens classes dependencies <fqn> --json` (count `outgoing`); the
incoming side / most-depended-on classes come from `codelens deps foundation`.

| Count | Impact |
|-------|--------|
| 0-3 | Low |
| 4-8 | Medium |
| > 8 | High |

**Why it matters:** Dependencies must be migrated first or adapted.

---

## Complexity Tiers

Useful qualitative buckets for communicating effort — assign them by judgment from the factor
mix above, not from a computed score. The hour ranges are rough estimates, not tool output.

| Tier | Typical Hours | Characteristics |
|------|---------------|-----------------|
| LOW | 1-4 | Simple logic, few dependencies, minimal Promise usage |
| MEDIUM | 4-12 | Moderate complexity, some integrations, standard patterns |
| HIGH | 12-24 | Complex Promise chains, multiple integrations, some anti-patterns |
| CRITICAL | 24+ | Deep nesting, many anti-patterns, heavy integration, may need redesign |

## Worked example

Gather the raw inputs, then write up the assessment yourself. For a handler where
`codelens source show` shows ~250 lines and 7 injected deps, `xref ratpack.exec.Promise` plus
`calls` show ~23 Promise operations nested ~5 deep, `xref` across the integration catalog finds
3 external clients, and the `ANTIPATTERNS.md` recipes confirm 2 ERROR-level blocking calls, you
might conclude:

```
com.example.OrderHandler — HIGH (~18h, judgment)
  ~250 LOC, HANDLER, 7 injected deps
  ~23 Promise ops, ~5-deep chains  ← dominant factor
  3 integrations; 2 confirmed ERROR anti-patterns
  Notes: deep chains map to async/await; review the blocking DB calls;
         consider splitting the handler.
```

The numbers are inputs you collected, not a score the tool returned; the tier and hours are
your call.

### Using the factors

1. **Prioritize by tier** - Start with LOW, progress to MEDIUM
2. **Weigh the heavy factors first** - chain depth and anti-patterns move the needle most
3. **Group related handlers** - migrate handlers with shared dependencies together
4. **Re-gather as you go** - re-run the recipes as you simplify code

## Migration order

There is no tool that computes an order. Rank classes by these factors, foundation-first, and
explain your reasoning. The most-depended-on classes (`codelens deps foundation`,
`codelens classes dependencies <fqn>`) usually migrate first because everything else builds on
them; among the rest, go in increasing complexity. Show the factors behind each ranking rather
than a single opaque number.
