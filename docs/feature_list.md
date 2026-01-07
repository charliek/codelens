# CodeLens Feature Catalog

This document provides a comprehensive catalog of all planned CodeLens features for LLM-assisted Ratpack migration.

## Feature Summary

| # | Feature | Priority | Phase | Description |
|---|---------|----------|-------|-------------|
| 1 | Handler Discovery & Classification | P0 | 2A | Find all Handler implementations, classify by type |
| 2 | Promise Usage Detection | P0 | 2A | Identify Promise patterns, operations, sources |
| 3 | Migration Complexity Scoring | P0 | 2A | Rate handlers by migration difficulty |
| 4 | Guice DI Analysis | P0 | 2A | Map modules, bindings, providers |
| 5 | Source Code Retrieval | P1 | 2B | Return actual source for classes/methods |
| 6 | External Service Detection | P1 | 2B | HTTP clients, databases, queues |
| 7 | Registry Access Analysis | P1 | 2B | ctx.get() usage patterns |
| 8 | API Versioning Detection | P1 | 2C | Version routing patterns |
| 9 | Pattern-Based Migration Hints | P2 | 2C | Pattern-specific recommendations |
| 10 | Route/Chain Analysis | P2 | 2C | URL structure and middleware |
| 11 | Anti-pattern Detection | P2 | 2C | Blocking code, Thread.sleep, etc. |
| 12 | Full Migration Report | P2 | 2D | Comprehensive planning document |
| 13 | Dependency Migration Graph | P2 | 2D | Migration ordering |
| 14 | OpenRewrite Recipe Generation | P3 | 3 | Auto-generate refactoring recipes |
| 15 | Test Migration Analysis | P3 | 3 | Test coverage and migration approach |
| 16 | MCP Server Wrapper | P3 | 3 | Direct Claude Desktop integration |
| 17 | Source-Level Parsing | P3 | 3 | Kotlin Analysis API / JavaParser |

---

## Priority Definitions

- **P0 (Critical)**: Required for MVP migration support. Without these, the tool cannot effectively assist migration.
- **P1 (High)**: Significantly improves migration quality and LLM effectiveness.
- **P2 (Medium)**: Valuable enhancements that improve user experience or handle edge cases.
- **P3 (Future)**: Nice-to-have features for advanced use cases.

---

## Feature 1: Handler Discovery & Classification

**Priority**: P0 - Critical
**Phase**: 2A
**Status**: Planned

### Description

Identifies all Ratpack handler implementations in the target project and classifies them by type. This is the foundational feature that determines what code needs to be migrated.

### Value Proposition

- **For LLMs**: Provides a complete inventory of migration targets
- **For Humans**: Quick overview of project scope and complexity distribution
- **For Planning**: Enables accurate effort estimation

### Detection Targets

| Type | Detection Method | Example |
|------|------------------|---------|
| `HANDLER_INTERFACE` | Implements `ratpack.handling.Handler` | `class UserHandler implements Handler` |
| `CHAIN_ACTION` | Implements `ratpack.func.Action<Chain>` | `class Api implements Action<Chain>` |
| `GROOVY_HANDLER` | Groovy DSL closures | `get("path") { ctx -> ... }` |

### CLI Commands

```bash
# List all handlers with complexity summary
codelens handlers list

# Filter by complexity level
codelens handlers list --complexity HIGH
codelens handlers list --complexity LOW,MEDIUM

# Filter by handler type
codelens handlers list --type HANDLER_INTERFACE
codelens handlers list --type CHAIN_ACTION

# JSON output for LLM consumption
codelens handlers list --json

# Get detailed info for specific handler
codelens handlers show com.example.UserHandler
codelens handlers show UserHandler  # Fuzzy match
```

### Output Examples

**Human-readable output:**
```
moonracer • 17 handlers • Last scanned: 2 min ago

Complexity Distribution:
████████ LOW (4)  ██████████ MEDIUM (8)  █████ HIGH (5)

┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┳━━━━━━━━━━━━┳━━━━━━━━━━━┳━━━━━━━━━━━┓
│ Handler                                      │ Type       │ Complexity│ Effort    │
┡━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━╇━━━━━━━━━━━━╇━━━━━━━━━━━╇━━━━━━━━━━━┩
│ c.s.moonracer.DeviceStateGetHandler          │ Handler    │ MEDIUM    │ ~4 hours  │
│ c.s.moonracer.DeviceStateUpdateHandler       │ Handler    │ HIGH      │ ~1 day    │
│ c.s.moonracer.LocationGroupsApi              │ Action     │ LOW       │ ~1 hour   │
│ c.s.moonracer.DevicesApi                     │ Action     │ MEDIUM    │ ~4 hours  │
│ c.s.moonracer.HealthHandler                  │ Handler    │ LOW       │ ~1 hour   │
└──────────────────────────────────────────────┴────────────┴───────────┴───────────┘

Tip: Use `codelens handlers show <name>` for detailed analysis
```

**JSON output (for LLM):**
```json
{
  "projectName": "moonracer",
  "scannedAt": "2026-01-07T10:30:00Z",
  "handlers": [
    {
      "fqn": "com.smartthings.moonracer.DeviceStateGetHandler",
      "simpleName": "DeviceStateGetHandler",
      "packageName": "com.smartthings.moonracer",
      "type": "HANDLER_INTERFACE",
      "complexity": {
        "level": "MEDIUM",
        "score": 3.5,
        "factors": ["promise_chain", "permission_check", "service_call"]
      },
      "sourceFile": "src/main/java/com/smartthings/moonracer/DeviceStateGetHandler.java",
      "dependencies": ["DeviceStateService", "SiamPermissionService"],
      "annotations": ["javax.inject.Singleton"]
    },
    {
      "fqn": "com.smartthings.moonracer.LocationGroupsApi",
      "simpleName": "LocationGroupsApi",
      "packageName": "com.smartthings.moonracer",
      "type": "CHAIN_ACTION",
      "complexity": {
        "level": "LOW",
        "score": 1.5,
        "factors": ["simple_routing"]
      },
      "sourceFile": "src/main/java/com/smartthings/moonracer/LocationGroupsApi.java",
      "dependencies": ["DeviceStateGetHandler", "DeviceStateUpdateHandler"],
      "annotations": []
    }
  ],
  "summary": {
    "total": 17,
    "byType": {
      "HANDLER_INTERFACE": 12,
      "CHAIN_ACTION": 5
    },
    "byComplexity": {
      "LOW": 4,
      "MEDIUM": 8,
      "HIGH": 5,
      "VERY_HIGH": 0
    }
  }
}
```

### Implementation Overview

**Server-side (Kotlin):**

1. **RatpackDetector class** in `server/classgraph` module:
   ```kotlin
   class RatpackDetector(private val scanResult: ScanResult) {
       fun findHandlers(): List<HandlerInfo> {
           val handlers = mutableListOf<HandlerInfo>()

           // Find Handler implementations
           scanResult.getClassesImplementing("ratpack.handling.Handler")
               .filter { it.isPublic && !it.isAbstract }
               .forEach { handlers.add(toHandlerInfo(it, HandlerType.HANDLER_INTERFACE)) }

           // Find Action<Chain> implementations
           scanResult.getClassesImplementing("ratpack.func.Action")
               .filter { isChainAction(it) }
               .forEach { handlers.add(toHandlerInfo(it, HandlerType.CHAIN_ACTION)) }

           return handlers
       }
   }
   ```

2. **New API endpoints**:
   - `GET /api/v1/ratpack/handlers` - List handlers with filters
   - `GET /api/v1/ratpack/handlers/{fqn}` - Get handler details

3. **New data models**:
   ```kotlin
   data class HandlerInfo(
       val fqn: String,
       val simpleName: String,
       val type: HandlerType,
       val complexity: ComplexityInfo,
       val sourceFile: String?,
       val dependencies: List<String>,
       val annotations: List<String>
   )

   enum class HandlerType {
       HANDLER_INTERFACE,
       CHAIN_ACTION,
       GROOVY_HANDLER
   }
   ```

**CLI-side (Python):**

1. **New command group** `handlers`:
   ```python
   @app.command()
   def handlers():
       """Ratpack handler analysis commands"""
       pass

   @handlers.command("list")
   def handlers_list(
       complexity: Optional[str] = None,
       type: Optional[str] = None,
       json_output: bool = False
   ):
       """List all Ratpack handlers"""
       ...
   ```

### Testing Strategy

- Unit tests with mocked ClassGraph results
- Integration tests using expanded `test-fixtures/sample-ratpack-app`
- Manual acceptance testing against real projects (not linked in repo)

---

## Feature 2: Promise Usage Detection & Analysis

**Priority**: P0 - Critical
**Phase**: 2A
**Status**: Planned

### Description

Detects and analyzes Promise usage patterns throughout the codebase. This is critical for understanding migration complexity since Promise chains are the primary async abstraction in Ratpack that need conversion.

### Value Proposition

- **For LLMs**: Identifies async patterns that need conversion
- **For Complexity Scoring**: Promise chain depth/complexity directly impacts migration effort
- **For Migration Planning**: Helps identify which patterns require special handling

### Detection Targets

| Pattern | Detection Method | Migration Complexity |
|---------|------------------|---------------------|
| `Blocking.get()` | Class dependency on `ratpack.exec.Blocking` | MEDIUM |
| `Promise.value()` | Method call detection | LOW |
| `Promise.async()` | Method call detection | HIGH |
| `Execution.fork()` | Class dependency on `ratpack.exec.Execution` | VERY_HIGH |
| `ParallelBatch` | Class dependency detection | HIGH |
| Promise operators | Method references to map/flatMap/etc. | Varies |

### CLI Commands

```bash
# Project-wide Promise usage summary
codelens promises summary

# Promise usage for specific class
codelens promises show com.example.DeviceStateService

# Find all uses of specific operation
codelens promises search --operation flatMap
codelens promises search --operation flatRight

# Find by Promise source
codelens promises search --source BLOCKING_GET
codelens promises search --source EXECUTION_FORK

# JSON output
codelens promises summary --json
```

### Output Examples

**Summary output:**
```
moonracer • Promise Usage Analysis

Sources:
  Blocking.get()      : 45 usages across 12 classes
  Promise.value()     : 23 usages across 8 classes
  Promise.async()     : 5 usages across 3 classes
  Execution.fork()    : 2 usages across 1 class
  ParallelBatch       : 3 usages across 2 classes

Operations:
  .map()              : 89 occurrences
  .flatMap()          : 67 occurrences
  .then()             : 52 occurrences
  .next()/.nextOp()   : 34 occurrences
  .onError()          : 28 occurrences
  .flatRight()        : 8 occurrences
  .retryIf()          : 5 occurrences

Classes with highest Promise complexity:
  DeviceStateService     : 12 chains, max depth 8
  LocationGroupService   : 8 chains, max depth 6
  ResetJobConsumerService: 6 chains, max depth 5
```

**JSON output (for LLM):**
```json
{
  "className": "com.smartthings.moonracer.DeviceStateService",
  "promiseUsages": [
    {
      "methodName": "getDeviceState",
      "lineNumber": 45,
      "chainLength": 5,
      "operations": ["flatMap", "map", "onError", "then"],
      "source": "BLOCKING_GET",
      "hasParallel": false,
      "hasRetry": false
    },
    {
      "methodName": "saveDeviceState",
      "lineNumber": 78,
      "chainLength": 8,
      "operations": ["nextOp", "flatMap", "flatRight", "map", "wiretap", "onError", "then"],
      "source": "SERVICE_CALL",
      "hasParallel": true,
      "hasRetry": true
    }
  ],
  "summary": {
    "totalChains": 12,
    "maxChainLength": 8,
    "avgChainLength": 4.2,
    "usesParallelBatch": true,
    "usesExecutionFork": false,
    "operationCounts": {
      "flatMap": 15,
      "map": 22,
      "next": 8,
      "flatRight": 3,
      "onError": 7
    },
    "sourceBreakdown": {
      "BLOCKING_GET": 5,
      "SERVICE_CALL": 4,
      "HTTP_CLIENT": 3
    }
  },
  "migrationHints": [
    "Convert flatMap chains to sequential suspend calls",
    "Replace flatRight(3) with async{} + await() for parallel execution",
    "retryIf patterns (1) can use kotlin-retry library"
  ]
}
```

### Implementation Overview

**Server-side:**

1. **PromiseDetector class**:
   ```kotlin
   class PromiseDetector(private val scanResult: ScanResult) {
       fun analyzeClass(fqn: String): PromiseAnalysis {
           val classInfo = scanResult.getClassInfo(fqn)

           return PromiseAnalysis(
               className = fqn,
               usesBlocking = hasBlockingDependency(classInfo),
               usesExecutionFork = hasExecutionDependency(classInfo),
               usesParallelBatch = hasParallelBatchDependency(classInfo),
               promiseOperations = detectPromiseOperations(classInfo)
           )
       }

       private fun hasBlockingDependency(classInfo: ClassInfo): Boolean {
           return classInfo.classDependencies.any {
               it.name == "ratpack.exec.Blocking"
           }
       }
   }
   ```

2. **New endpoints**:
   - `GET /api/v1/ratpack/promises` - Project-wide summary
   - `GET /api/v1/ratpack/promises/{fqn}` - Class-specific analysis
   - `GET /api/v1/ratpack/promises/search` - Search by operation/source

---

## Feature 3: Migration Complexity Scoring

**Priority**: P0 - Critical
**Phase**: 2A
**Status**: Planned

### Description

Calculates a complexity score for each handler/class based on multiple factors, helping prioritize migration order and estimate effort.

### Value Proposition

- **For LLMs**: Helps prioritize which handlers to tackle first
- **For Planning**: Provides effort estimates for project timelines
- **For Risk Assessment**: Identifies high-risk areas needing careful attention

### Complexity Factors

| Factor | Weight | Description |
|--------|--------|-------------|
| Base score | 1.0 | Every handler starts here |
| Blocking.get() usage | +0.5 per use | Needs withContext(IO) conversion |
| Promise.async() usage | +2.0 per use | Complex callback conversion |
| Execution.fork() usage | +3.0 per use | Requires architectural rethink |
| ParallelBatch usage | +2.0 per use | Needs async{} coordination |
| Promise chain depth > 3 | +0.5 per level | Deep chains harder to unwind |
| Registry access | +0.5 per unique type | Dynamic DI needs refactoring |
| External service calls | +0.5 per service | Integration points need attention |
| Anti-patterns | +1.0-2.0 | Must fix before/during migration |

### Complexity Levels

| Level | Score Range | Estimated Effort | Description |
|-------|-------------|------------------|-------------|
| LOW | 1.0 - 2.0 | ~1 hour | Direct port, minimal changes |
| MEDIUM | 2.0 - 4.0 | ~4 hours | Standard async conversion |
| HIGH | 4.0 - 7.0 | ~1 day | Complex chains, multiple concerns |
| VERY_HIGH | 7.0+ | ~1 week | Architectural changes needed |

### CLI Commands

```bash
# Project-wide complexity report
codelens migration complexity

# Complexity for specific class
codelens migration complexity com.example.UserHandler

# Detailed breakdown
codelens migration complexity com.example.UserHandler --detailed

# Get recommended migration order
codelens migration order
codelens migration order --strategy complexity-asc  # Easiest first
codelens migration order --strategy dependency      # Dependency order

# JSON output
codelens migration complexity --json
```

### Output Examples

**Detailed complexity output:**
```json
{
  "class": "com.smartthings.moonracer.DeviceStateService",
  "complexity": {
    "score": 7.5,
    "level": "HIGH",
    "estimatedEffort": "~1 day"
  },
  "factors": [
    {
      "name": "Promise chain depth",
      "count": 8,
      "contribution": 2.0,
      "details": "Max depth 8 in saveDeviceState()"
    },
    {
      "name": "Blocking.get() usage",
      "count": 5,
      "contribution": 2.5,
      "details": "Convert to withContext(Dispatchers.IO)"
    },
    {
      "name": "ParallelBatch usage",
      "count": 1,
      "contribution": 2.0,
      "details": "Requires coroutineScope + async{}"
    },
    {
      "name": "External service dependencies",
      "count": 3,
      "contribution": 1.5,
      "details": "BunsenClient, SiamService, DeviceStateDAO"
    }
  ],
  "blockers": [
    {
      "type": "DEPENDENCY",
      "description": "Depends on DeviceStateDAO (not yet migrated)"
    },
    {
      "type": "EXTERNAL",
      "description": "Uses SiamPermissionService (external service)"
    }
  ],
  "recommendations": [
    "Migrate DeviceStateDAO first",
    "Extract retry logic to separate utility class",
    "Consider breaking into smaller services"
  ]
}
```

### Implementation Overview

```kotlin
class ComplexityCalculator {
    fun calculate(
        handlerInfo: HandlerInfo,
        promiseAnalysis: PromiseAnalysis,
        dependencyGraph: DependencyGraph
    ): ComplexityResult {
        var score = 1.0
        val factors = mutableListOf<ComplexityFactor>()

        // Promise source complexity
        promiseAnalysis.sources.forEach { source ->
            val contribution = when (source.type) {
                PromiseSource.BLOCKING_GET -> 0.5
                PromiseSource.PROMISE_ASYNC -> 2.0
                PromiseSource.EXECUTION_FORK -> 3.0
                PromiseSource.PARALLEL_BATCH -> 2.0
                else -> 0.5
            }
            score += contribution * source.count
            factors.add(ComplexityFactor(source.type.name, contribution * source.count, source.count))
        }

        // Chain depth
        if (promiseAnalysis.maxChainDepth > 3) {
            val depthContribution = (promiseAnalysis.maxChainDepth - 3) * 0.5
            score += depthContribution
            factors.add(ComplexityFactor("Promise chain depth", depthContribution, promiseAnalysis.maxChainDepth))
        }

        // ... additional factors

        val level = when {
            score <= 2.0 -> ComplexityLevel.LOW
            score <= 4.0 -> ComplexityLevel.MEDIUM
            score <= 7.0 -> ComplexityLevel.HIGH
            else -> ComplexityLevel.VERY_HIGH
        }

        return ComplexityResult(score, level, factors, estimateEffort(level))
    }
}
```

---

## Feature 4: Guice Dependency Injection Analysis

**Priority**: P0 - Critical
**Phase**: 2A
**Status**: Planned

### Description

Analyzes Guice module configurations, bindings, and providers to understand the dependency injection setup. Essential for translating to Spring/Micronaut DI.

### Value Proposition

- **For LLMs**: Provides DI configuration needed for Spring @Bean generation
- **For Migration**: Maps Guice patterns to Spring/Micronaut equivalents
- **For Understanding**: Reveals service graph and scoping

### Detection Targets

| Pattern | Detection | Spring Equivalent |
|---------|-----------|-------------------|
| `bind(X).to(Y)` | Bytecode analysis | `@Component` on Y |
| `bind(X).to(Y).in(SINGLETON)` | Scope annotations | `@Component` (default) |
| `@Provides` method | Annotation scan | `@Bean` method |
| `@Provides @Singleton` | Annotation scan | `@Bean` method |
| `@ProvidesIntoSet` | Annotation scan | `@Bean` returning collection |
| `ConfigurableModule` | Inheritance detection | `@ConfigurationProperties` |

### CLI Commands

```bash
# List all Guice modules
codelens modules list

# Show bindings in a module
codelens modules show com.example.AppModule

# Find what provides a specific type
codelens modules binding com.example.UserService

# Show DI graph
codelens modules graph
codelens modules graph --format dot > di-graph.dot

# JSON output
codelens modules list --json
```

### Output Examples

**Module list output:**
```
moonracer • 3 Guice Modules

┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┳━━━━━━━━━━━━━━━━━━━━━━┳━━━━━━━━━━━┓
│ Module                                   │ Type                 │ Bindings  │
┡━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━╇━━━━━━━━━━━━━━━━━━━━━━╇━━━━━━━━━━━┩
│ c.s.moonracer.MoonracerModule           │ ConfigurableModule   │ 28        │
│ c.s.moonracer.ClientModule              │ AbstractModule       │ 12        │
│ c.s.moonracer.DaoModule                 │ AbstractModule       │ 5         │
└─────────────────────────────────────────┴──────────────────────┴───────────┘
```

**JSON output (for LLM):**
```json
{
  "modules": [
    {
      "fqn": "com.smartthings.moonracer.MoonracerModule",
      "simpleName": "MoonracerModule",
      "type": "CONFIGURABLE_MODULE",
      "configClass": "com.smartthings.moonracer.MoonracerConfig",
      "bindings": [
        {
          "boundType": "com.smartthings.moonracer.DeviceStateService",
          "implementation": "com.smartthings.moonracer.DefaultDeviceStateService",
          "scope": "SINGLETON",
          "bindingType": "DIRECT"
        },
        {
          "boundType": "ratpack.http.client.HttpClient",
          "implementation": null,
          "scope": "SINGLETON",
          "bindingType": "PROVIDER",
          "providerMethod": "httpClient(MoonracerConfig)"
        }
      ],
      "multiBindings": [
        {
          "setType": "ratpack.parse.Parser",
          "implementations": ["ApiJsonParser", "ProtobufParser"],
          "bindingType": "INTO_SET"
        }
      ],
      "installedModules": ["ClientModule", "DaoModule"]
    }
  ],
  "migrationMapping": {
    "bind(X).to(Y).in(SINGLETON)": "@Component on Y class, or @Bean method returning Y",
    "@Provides @Singleton": "@Bean method in @Configuration class",
    "@ProvidesIntoSet": "@Bean returning collection element, use List<> injection",
    "ConfigurableModule<C>": "@ConfigurationProperties for C, inject via constructor"
  }
}
```

---

## Feature 5: Source Code Retrieval

**Priority**: P1 - High
**Phase**: 2B
**Status**: Planned

### Description

Returns actual source code for classes and methods. Essential for LLMs to generate migration code since they need to see the original implementation.

### Value Proposition

- **For LLMs**: Provides actual code to transform (not just metadata)
- **For Context**: LLM can understand logic, not just structure
- **For Accuracy**: Enables precise code generation

### CLI Commands

```bash
# Get full source for a class
codelens source show com.example.UserHandler

# Get source for specific method
codelens source method com.example.UserService.getUser

# Get source with context lines
codelens source show com.example.UserHandler --context 10

# JSON output
codelens source show com.example.UserHandler --json
```

### Output Example

```json
{
  "class": "com.smartthings.moonracer.DeviceStateGetHandler",
  "sourceFile": "/path/to/project/src/main/java/com/smartthings/moonracer/DeviceStateGetHandler.java",
  "language": "java",
  "source": "package com.smartthings.moonracer;\n\nimport ratpack.handling.Context;\nimport ratpack.handling.Handler;\nimport javax.inject.Inject;\nimport javax.inject.Singleton;\n\n@Singleton\npublic class DeviceStateGetHandler implements Handler {\n    private final DeviceStateService service;\n    private final SiamPermissionService permissionService;\n    \n    @Inject\n    public DeviceStateGetHandler(\n            DeviceStateService service,\n            SiamPermissionService permissionService) {\n        this.service = service;\n        this.permissionService = permissionService;\n    }\n    \n    @Override\n    public void handle(Context ctx) {\n        String locationId = ctx.getPathTokens().get(\"locationId\");\n        String deviceId = ctx.getPathTokens().get(\"deviceId\");\n        \n        permissionService.checkLocationRead(locationId)\n            .flatMap(v -> service.getDeviceState(locationId, deviceId))\n            .map(ApiJsonMapper::toResponse)\n            .onError(ctx::error)\n            .then(ctx::render);\n    }\n}",
  "methods": [
    {
      "name": "handle",
      "startLine": 20,
      "endLine": 32,
      "signature": "public void handle(Context ctx)"
    }
  ]
}
```

### Implementation Overview

1. Resolve source roots from Gradle project model (already available)
2. Map FQN to source file path using package structure
3. Read file contents
4. Optionally extract specific method using line numbers from debug info

---

## Feature 6: External Service Integration Detection

**Priority**: P1 - High
**Phase**: 2B
**Status**: Planned

### Description

Identifies HTTP clients, database connections, message queues, and other external service integrations. These are critical migration points that often require special attention.

### Detection Targets

| Integration Type | Detection Method |
|-----------------|------------------|
| HTTP Clients | Dependency on `ratpack.http.client.HttpClient` |
| DynamoDB | AWS SDK class usage |
| SQS/SNS | AWS SDK class usage |
| JDBC | `java.sql.*` imports |
| Redis | Lettuce/Jedis class usage |
| gRPC | gRPC stub class usage |

### CLI Commands

```bash
# List all integrations
codelens integrations list

# Filter by type
codelens integrations list --type HTTP_CLIENT
codelens integrations list --type DATABASE

# Show integration details
codelens integrations show com.example.BunsenClient

# JSON output
codelens integrations list --json
```

### Output Example

```json
{
  "integrations": [
    {
      "type": "HTTP_CLIENT",
      "className": "com.smartthings.moonracer.BunsenClient",
      "targetService": "bunsen-service",
      "methods": ["getLocationGroup", "getLocationGroups", "getLocationGroupsPaged"],
      "features": {
        "usesRetry": true,
        "usesCircuitBreaker": false,
        "usesTimeout": true
      },
      "dependentClasses": ["LocationGroupService", "DeviceStateService"]
    },
    {
      "type": "DATABASE",
      "className": "com.smartthings.moonracer.DeviceStateDAO",
      "technology": "DynamoDB",
      "operations": ["get", "put", "query", "batchGet"],
      "tables": ["device-states"],
      "dependentClasses": ["DeviceStateService"]
    },
    {
      "type": "MESSAGE_QUEUE",
      "className": "com.smartthings.moonracer.ResetJobConsumerService",
      "technology": "SQS",
      "pattern": "CONSUMER",
      "queueName": "reset-jobs-queue",
      "dependentClasses": []
    }
  ],
  "summary": {
    "httpClients": 11,
    "databases": 2,
    "messageQueues": 2,
    "caches": 1
  }
}
```

---

## Feature 7: Registry Access Analysis

**Priority**: P1 - High
**Phase**: 2B
**Status**: Planned

### Description

Identifies `ctx.get()`, `ctx.maybeGet()`, and other Registry access patterns. These dynamic DI lookups need to be converted to constructor injection in Spring/Micronaut.

### CLI Commands

```bash
# Find all registry access
codelens registry usages

# Show for specific class
codelens registry show com.example.UserHandler

# JSON output
codelens registry usages --json
```

### Output Example

```json
{
  "class": "com.smartthings.moonracer.DevicesApi",
  "registryAccess": [
    {
      "method": "get",
      "accessedType": "com.smartthings.moonracer.DeviceStateGetHandler",
      "location": "line 45",
      "pattern": "ctx.get(DeviceStateGetHandler.class)",
      "migrationHint": "Inject DeviceStateGetHandler via constructor"
    },
    {
      "method": "maybeGet",
      "accessedType": "com.smartthings.RequestContext",
      "location": "line 52",
      "pattern": "RequestContext.maybeGet()",
      "migrationHint": "Use @RequestScope bean or pass as method parameter"
    }
  ],
  "summary": {
    "totalAccess": 8,
    "byMethod": {
      "get": 5,
      "maybeGet": 2,
      "getAll": 1
    }
  }
}
```

---

## Feature 8: API Versioning Detection

**Priority**: P1 - High
**Phase**: 2C
**Status**: Planned

### Description

Detects API versioning strategies used in the codebase (path-based, header-based, etc.) and maps handlers to versions.

### CLI Commands

```bash
# Show API versions
codelens api versions

# Show handlers per version
codelens api show v20210405

# JSON output
codelens api versions --json
```

### Output Example

```json
{
  "versioningStrategy": "CONTEXT_BASED",
  "versions": [
    {
      "version": "v1",
      "handlers": ["DeviceStateGetHandler", "DeviceStateUpdateHandler", "DeviceStateListHandler"],
      "isDefault": true
    },
    {
      "version": "v20210405",
      "handlers": ["DeviceStateGetHandler_v20210405"],
      "isDefault": false,
      "changes": ["Added pagination support", "Changed response format"]
    },
    {
      "version": "v20241029",
      "handlers": ["DeviceStateGetHandler_v20241029", "DeviceStateUpdateHandler_v20241029"],
      "isDefault": false,
      "changes": ["Batch operations support"]
    }
  ],
  "routingPatterns": [
    "chain.when(ApiVersionContext.is(V2), this::v2)",
    "chain.when(ApiVersionContext.is(V20241029), this::v20241029)"
  ]
}
```

---

## Feature 9: Pattern-Based Migration Hints

**Priority**: P2 - Medium
**Phase**: 2C
**Status**: Planned

### Description

Provides pattern-specific migration recommendations and code snippets. Lower priority since LLMs can generate code from analysis data.

### Output Example

```json
{
  "detectedPattern": "BLOCKING_GET_CHAIN",
  "originalCode": "Blocking.get(() -> userDao.findById(id)).map(User::toDto).then(ctx::render)",
  "hints": {
    "kotlin-spring": {
      "pattern": "suspend function with withContext",
      "example": "suspend fun getUser(id: String): UserDto = withContext(Dispatchers.IO) { userDao.findById(id) }?.toDto()"
    },
    "java-spring": {
      "pattern": "blocking controller method",
      "example": "@GetMapping public UserDto getUser(@PathVariable String id) { return userDao.findById(id).toDto(); }"
    }
  }
}
```

---

## Feature 10: Route/Chain Analysis

**Priority**: P2 - Medium
**Phase**: 2C
**Status**: Planned

### Description

Analyzes route definitions, path parameters, middleware chains, and HTTP method mappings.

### Output Example

```json
{
  "routes": [
    {
      "path": "/locationgroups/:locationGroupId/locations/:locationId/devices/:deviceId",
      "methods": ["GET"],
      "handler": "DeviceStateGetHandler",
      "middleware": ["BearerTokenAuthHandler", "RequireAuthHandler", "RateLimitHandler"],
      "pathParams": ["locationGroupId", "locationId", "deviceId"]
    }
  ],
  "middleware": [
    {
      "name": "BearerTokenAuthHandler",
      "appliedTo": "all routes under /api",
      "purpose": "Authentication"
    }
  ]
}
```

---

## Feature 11: Anti-pattern Detection

**Priority**: P2 - Medium
**Phase**: 2C
**Status**: Planned

### Description

Detects code patterns that block the event loop or violate Ratpack best practices. These should be fixed before or during migration.

### Detection Targets

| Anti-pattern | Severity | Fix |
|--------------|----------|-----|
| JDBC without Blocking | HIGH | Wrap in Blocking.get() or use async driver |
| Thread.sleep() | MEDIUM | Replace with delay() in coroutines |
| Unsubscribed Promise | MEDIUM | Ensure Promise is consumed |
| Synchronous file I/O | MEDIUM | Use async file APIs or Blocking |

### Output Example

```json
{
  "antiPatterns": [
    {
      "type": "BLOCKING_JDBC",
      "severity": "HIGH",
      "class": "LegacyReportHandler",
      "method": "handle",
      "line": 45,
      "description": "Direct JDBC call without Blocking.get() wrapper",
      "fix": "Wrap in Blocking.get() before migration, or convert to R2DBC"
    },
    {
      "type": "THREAD_SLEEP",
      "severity": "MEDIUM",
      "class": "BatchProcessor",
      "method": "process",
      "line": 78,
      "description": "Thread.sleep() blocks compute thread",
      "fix": "Replace with delay() in coroutines or Ratpack's Throttle"
    }
  ]
}
```

---

## Feature 12: Full Migration Report

**Priority**: P2 - Medium
**Phase**: 2D
**Status**: Planned

### Description

Generates a comprehensive migration planning document combining all analysis results.

### CLI Commands

```bash
# Generate report
codelens report generate
codelens report generate --format markdown -o migration-report.md
codelens report generate --format json -o report.json
```

---

## Feature 13: Dependency Migration Graph

**Priority**: P2 - Medium
**Phase**: 2D
**Status**: Planned

### Description

Generates a graph showing optimal migration order based on dependencies.

---

## Features 14-17: Phase 3 (Future)

**Feature 14: OpenRewrite Recipe Generation** - Auto-generate transformation recipes
**Feature 15: Test Migration Analysis** - Analyze test coverage and migration approach
**Feature 16: MCP Server Wrapper** - Direct Claude Desktop/Code integration
**Feature 17: Source-Level Parsing** - Kotlin Analysis API for deeper analysis

---

## Appendix: Ratpack Type Constants

```kotlin
object RatpackTypes {
    const val HANDLER = "ratpack.handling.Handler"
    const val CONTEXT = "ratpack.handling.Context"
    const val PROMISE = "ratpack.exec.Promise"
    const val BLOCKING = "ratpack.exec.Blocking"
    const val EXECUTION = "ratpack.exec.Execution"
    const val CHAIN = "ratpack.handling.Chain"
    const val ACTION = "ratpack.func.Action"
    const val PARALLEL_BATCH = "ratpack.exec.util.ParallelBatch"
    const val GUICE_MODULE = "com.google.inject.Module"
    const val GUICE_ABSTRACT_MODULE = "com.google.inject.AbstractModule"
    const val CONFIGURABLE_MODULE = "ratpack.guice.ConfigurableModule"
    const val REGISTRY = "ratpack.registry.Registry"
}
```
