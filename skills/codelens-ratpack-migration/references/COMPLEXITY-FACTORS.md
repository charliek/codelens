# Complexity Factors Reference

CodeLens calculates migration complexity using weighted factors.

## Score Calculation

Total score = Sum of (factor weight × factor value), normalized to 0-100.

## Factors

### Lines of Code

**Weight:** Low
**Description:** Raw size of the handler implementation.

| LOC | Impact |
|-----|--------|
| < 50 | Minimal |
| 50-150 | Low |
| 150-300 | Medium |
| > 300 | High |

**Why it matters:** Larger classes take longer to understand and migrate.

---

### Handler Type

**Weight:** Low-Medium
**Description:** Type of Ratpack handler.

| Type | Impact |
|------|--------|
| HANDLER | Low |
| CHAIN_ACTION | Medium |
| INLINE_HANDLER | Low |
| GROOVY_HANDLER | Medium-High |

**Why it matters:** Chain actions require understanding routing structure. Groovy handlers may need language translation.

---

### Injected Dependencies

**Weight:** Medium
**Description:** Number of @Inject dependencies.

| Count | Impact |
|-------|--------|
| 0-2 | Low |
| 3-5 | Medium |
| 6-10 | High |
| > 10 | Very High |

**Why it matters:** Each dependency must be understood and potentially migrated.

---

### Promise Operations

**Weight:** Medium-High
**Description:** Number and type of Promise operations.

| Aspect | Weight |
|--------|--------|
| Total operations | Medium |
| Blocking.get usage | Medium |
| flatMap chains | High |
| ParallelBatch | High |

**Why it matters:** Promise patterns must be translated to target framework's async model.

---

### Promise Chain Depth

**Weight:** High
**Description:** Maximum nesting level of Promise operations.

| Depth | Impact |
|-------|--------|
| 1-2 | Low |
| 3-4 | Medium |
| 5-7 | High |
| > 7 | Very High |

**Why it matters:** Deep chains are hard to understand and translate.

---

### External Integrations

**Weight:** Medium
**Description:** Number of external service integrations.

| Type | Weight |
|------|--------|
| HTTP Client | Medium |
| Database | Medium-High |
| Message Queue | Medium |
| Cache | Low |
| gRPC | Medium |

**Why it matters:** Each integration may have different migration patterns.

---

### Anti-Patterns

**Weight:** Medium-High
**Description:** Number and severity of detected anti-patterns.

| Severity | Weight |
|----------|--------|
| INFO | Very Low |
| WARNING | Low |
| ERROR | Medium |
| CRITICAL | High |

**Why it matters:** Anti-patterns indicate code that needs fixing, adding migration effort.

---

### Dependency Count

**Weight:** Low-Medium
**Description:** Number of classes this handler depends on.

| Count | Impact |
|-------|--------|
| 0-3 | Low |
| 4-8 | Medium |
| > 8 | High |

**Why it matters:** Dependencies must be migrated first or adapted.

---

## Complexity Tiers

| Tier | Score Range | Typical Hours | Characteristics |
|------|-------------|---------------|-----------------|
| LOW | 0-25 | 1-4 | Simple logic, few dependencies, minimal Promise usage |
| MEDIUM | 26-50 | 4-12 | Moderate complexity, some integrations, standard patterns |
| HIGH | 51-75 | 12-24 | Complex Promise chains, multiple integrations, some anti-patterns |
| CRITICAL | 76-100 | 24+ | Deep nesting, many anti-patterns, heavy integration, may need redesign |

## Interpreting Results

### Example Output

```
Handler: com.example.OrderHandler
Score: 67/100 (HIGH)
Estimated Hours: 18

Factors:
  Lines of Code:        250 (+8)
  Handler Type:         HANDLER (+2)
  Injected Deps:        7 (+12)
  Promise Operations:   23 (+15)
  Promise Chain Depth:  5 (+18)
  Integrations:         3 (+9)
  Anti-Patterns:        2 ERROR (+3)

Migration Notes:
  - Consider breaking into smaller handlers
  - Deep Promise chains suggest async/await pattern
  - Review blocking database calls
```

### Using the Data

1. **Prioritize by tier** - Start with LOW, progress to MEDIUM
2. **Address factors** - High-weight factors (chain depth, anti-patterns) first
3. **Group related handlers** - Migrate handlers with shared dependencies together
4. **Track progress** - Re-run analysis as you simplify code

## Commands

```bash
# Project summary
codelens migration complexity

# Specific class
codelens migration complexity com.example.OrderHandler

# Migration order (accounts for dependencies + complexity)
codelens migration order
```
