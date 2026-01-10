# Phase 2D: Dependency Analysis

**Status**: Complete ✅
**Completed**: 2026-01-09
**Prerequisite**: Phase 2C complete
**Target**: Project-wide dependency graph analysis for migration planning

---

## Overview

Phase 2D provides dependency graph analysis that answers key questions when approaching an unfamiliar Ratpack codebase:
- **What are the key dependencies?** - Foundation classes many things depend on
- **Are there circular dependencies?** - Problems that need refactoring
- **Where do I start?** - Entry points with few dependencies (quick wins)
- **What's the dependency structure?** - Handlers grouped by dependency depth

**Why this feature (vs LLM aggregation):** The graph algorithms (cycle detection, tier grouping, foundation class identification) require server-side computation. An LLM calling existing commands cannot efficiently replicate this.

**Out of scope:** Codebase report aggregation - existing commands + LLM skill can handle this more flexibly.

**Success Criteria**:
- Detect circular dependencies in handler/service graph
- Identify foundation classes (most depended-on)
- Group handlers by dependency tier
- Generate Graphviz DOT output for visualization

---

## Current State (Dependencies Validated)

All required analyzers are **implemented and functional**:
| Analyzer | Purpose | Status |
|----------|---------|--------|
| `RatpackDetector` | Handler discovery | Done |
| `ComplexityCalculator` | Complexity scoring | Done |

The existing `codelens classes deps <fqn>` command provides single-class dependency analysis. This feature extends it to **project-wide** dependency graph analysis.

---

## Feature: Dependency Analysis

### What It Shows

Actionable insights about dependencies to help plan approach:

```
# Dependency Analysis

## Foundation Classes (migrate these first - many dependents)
| Class | Dependents | Type |
|-------|------------|------|
| UserService | 15 handlers | SERVICE |
| OrderRepository | 12 handlers | REPOSITORY |
| CacheManager | 10 handlers | UTILITY |

## Quick Wins (few dependencies - easy starting points)
| Handler | Dependencies | Complexity |
|---------|--------------|------------|
| HealthHandler | 0 | LOW |
| VersionHandler | 0 | LOW |
| MetricsHandler | 1 | LOW |

## Circular Dependencies (refactor before migration)
- OrderHandler -> PaymentService -> OrderHandler
- UserHandler -> ProfileService -> UserHandler

## Handler Dependency Tiers
- Tier 0 (no dependencies): 8 handlers - start here
- Tier 1 (depends only on Tier 0): 12 handlers
- Tier 2 (depends on Tier 1): 15 handlers
- Tier 3+: 12 handlers
```

### Data Models

**File:** `server/core/src/main/kotlin/codelens/core/model/ratpack/DependencyModels.kt`

```kotlin
@Serializable
data class DependencyAnalysis(
    val foundationClasses: List<FoundationClass>,  // Most depended-on
    val quickWins: List<QuickWinHandler>,          // Handlers with 0-1 deps
    val cycles: List<DependencyCycle>,             // Circular deps
    val handlerTiers: List<DependencyTier>,        // Grouped by depth
    val stats: DependencyStats
)

@Serializable
data class FoundationClass(
    val fqn: String,
    val simpleName: String,
    val type: ClassType,  // SERVICE, REPOSITORY, UTILITY, HANDLER
    val dependentCount: Int,
    val dependentHandlers: List<String>  // Simple names
)

@Serializable
data class QuickWinHandler(
    val fqn: String,
    val simpleName: String,
    val dependencyCount: Int,
    val complexity: ComplexityTier
)

@Serializable
data class DependencyCycle(
    val classes: List<String>,  // FQNs in cycle order
    val description: String     // "A -> B -> A"
)

@Serializable
data class DependencyTier(
    val tier: Int,
    val description: String,  // "No dependencies", "Depends only on Tier 0"
    val handlers: List<String>,
    val count: Int
)

@Serializable
data class DependencyStats(
    val totalHandlers: Int,
    val totalDependencies: Int,
    val avgDependenciesPerHandler: Double,
    val maxDependencies: Int,
    val cycleCount: Int
)
```

### Implementation

**File:** `server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/DependencyAnalyzer.kt`

Key algorithms:
- Build dependency graph from handlers + project services
- Tarjan's algorithm for cycle detection
- BFS for tier grouping
- Foundation class identification (top N by dependent count)
- DOT format generation for Graphviz

Design decisions:
- **Scope:** Handlers + project-level services they depend on (not library classes)
- **Foundation detection:** Classes with ≥3 handler dependents
- **Tier calculation:** BFS from zero-dependency handlers

### API Endpoints

```kotlin
GET /api/v1/ratpack/dependencies           // Full analysis JSON
GET /api/v1/ratpack/dependencies?format=dot // Graphviz DOT
GET /api/v1/ratpack/dependencies/foundation // Just foundation classes
GET /api/v1/ratpack/dependencies/quickwins  // Just quick wins
```

### CLI Commands

```bash
codelens deps                      # Summary to terminal
codelens deps --full               # Full analysis
codelens deps --format dot -o deps.dot
codelens deps foundation           # Just foundation classes
codelens deps quickwins            # Just quick wins
```

---

## Files to Create/Modify

### New Files
| File | Description | Status |
|------|-------------|--------|
| `server/core/src/main/kotlin/codelens/core/model/ratpack/DependencyModels.kt` | Dependency analysis data models | ✅ Done |
| `server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/DependencyAnalyzer.kt` | Graph algorithms & analysis | ✅ Done |
| `cli/src/codelens_cli/commands/deps.py` | Dependency CLI commands | ✅ Done |
| `server/classgraph/src/test/kotlin/codelens/classgraph/ratpack/DependencyAnalyzerTest.kt` | Unit tests for graph algorithms | ✅ Done |

### Modified Files
| File | Changes | Status |
|------|---------|--------|
| `server/app/src/main/kotlin/codelens/server/routes/RatpackRoutes.kt` | Add `/dependencies` endpoints | ✅ Done |
| `server/app/src/main/kotlin/codelens/server/services/RatpackAnalysisService.kt` | Add dependency analysis methods | ✅ Done |
| `cli/src/codelens_cli/main.py` | Register `deps` command group | ✅ Done |
| `cli/src/codelens_cli/client.py` | Add dependency API client methods | ✅ Done |

---

## Implementation Order

1. Create `DependencyModels.kt` with all data classes
2. Implement `DependencyAnalyzer` with graph algorithms
3. Add methods to `RatpackAnalysisService`
4. Add endpoints to `RatpackRoutes.kt`
5. Create `deps.py` CLI commands
6. Add client methods to `client.py`
7. Register in `main.py`
8. Write unit tests for graph algorithms
9. Integration tests for API endpoints
10. Test against example ratpack apps

---

## Verification

```bash
# Build and test
./gradlew test

# Manual testing with sample project
codelens start -p test-fixtures/sample-ratpack-app

# Verify dependency analysis
codelens deps                      # Summary view
codelens deps --full               # Full analysis
codelens deps foundation           # Just foundation classes
codelens deps quickwins            # Just quick wins
codelens deps --format dot -o deps.dot && dot -Tpng deps.dot -o deps.png

# API testing
curl http://localhost:8080/api/v1/ratpack/dependencies | jq
curl http://localhost:8080/api/v1/ratpack/dependencies/foundation | jq
curl "http://localhost:8080/api/v1/ratpack/dependencies?format=dot"
```

---

## Decisions Log

| Date | Decision | Rationale |
|------|----------|-----------|
| 2025-01-09 | Skip codebase report feature | LLM + existing commands can aggregate data more flexibly |
| 2025-01-09 | Focus on dependency analysis | Graph algorithms provide genuine value-add that LLM cannot replicate |
| 2025-01-09 | Include handlers + project services | Balance between too narrow (handlers only) and too broad (all classes) |

---

## Completion Checklist

- [x] DependencyModels.kt created
- [x] DependencyAnalyzer implemented with cycle detection
- [x] API endpoints added
- [x] CLI commands working
- [x] Unit tests passing (20 tests)
- [x] Tested against ratpack-migration example apps (moonracer, pumbaa)
- [x] Plan file updated with completion status

---

## Implementation Notes & Deviations

### Additional Features Added
- **`deps graph` command**: Added a separate subcommand for graph visualization with high-impact node summary
- **Graph data models**: Added `DependencyGraph`, `DependencyNode`, `DependencyEdge` for visualization support
- **`/api/v1/ratpack/dependencies/graph` endpoint**: Separate endpoint for graph structure

### Algorithm Details
- **Cycle detection**: Used DFS with path tracking rather than Tarjan's SCC algorithm (simpler, sufficient for this use case)
- **Tier grouping**: Iterative assignment rather than pure BFS (handles cycles by assigning them to highest tier + 1)
- **Foundation threshold**: Set at ≥3 handler dependents (configurable via companion object constant)
- **Quick win criteria**: ≤1 dependency AND LOW or MEDIUM complexity

### Testing Results
| Project | Handlers | Dependencies | Foundation Classes | Quick Wins | Cycles |
|---------|----------|--------------|-------------------|------------|--------|
| moonracer | 21 | 69 | 5 (TokenScopePermissionService, DeviceStateService, etc.) | 5 | 0 |
| pumbaa | 28 | 39 | 4 (RequestValidationService, ResidentService, etc.) | 10 | 0 |

### Design Decisions
- **Constructor injection for testing**: Modified `DependencyAnalyzer` to accept optional `RatpackDetector` and `ComplexityCalculator` overrides for unit testing
- **Scope limited to PROJECT classes**: Only analyzes dependencies on project-level services, not library classes (reduces noise)
- **Duplicate handler names in output**: Handlers with same simple name in different packages (e.g., `v20210405.DeviceStateDeleteHandler` and `v20241029.DeviceStateDeleteHandler`) appear as duplicates in tier listings - this is expected behavior
