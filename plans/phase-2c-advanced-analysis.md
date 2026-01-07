# Phase 2C: Advanced Analysis

**Status**: Not Started
**Prerequisite**: Phase 2B complete
**Target**: Deeper understanding for complex migrations
**Features**: 8-11 (API Versioning, Migration Hints, Route Analysis, Anti-patterns)

---

## Overview

Phase 2C adds advanced analysis capabilities that help with complex migration scenarios. These features address patterns discovered in real projects like versioned APIs, route structures, and code anti-patterns.

**Success Criteria**:
- Detects API versioning strategies
- Provides useful migration hints/recommendations
- Maps route structure for Spring @RequestMapping generation
- Identifies anti-patterns that need fixing

---

## Feature 8: API Versioning Detection

### 8.1 Data Models

#### Server Models (`server/core/src/main/kotlin/codelens/core/model/ratpack/ApiVersionModels.kt`)

```kotlin
package codelens.core.model.ratpack

import kotlinx.serialization.Serializable

/**
 * Strategy used for API versioning.
 */
@Serializable
enum class VersioningStrategy {
    /** Version in URL path: /api/v1/users */
    PATH_BASED,
    /** Version in header: X-API-Version or Accept header */
    HEADER_BASED,
    /** Version via ApiVersionContext injection */
    CONTEXT_BASED,
    /** Version suffix on handler class names: UserHandler_v20210405 */
    CLASS_SUFFIX,
    /** Multiple strategies detected */
    MIXED,
    /** No versioning detected */
    NONE
}

/**
 * Detected API version information.
 */
@Serializable
data class ApiVersion(
    /** Version identifier (e.g., "v1", "20210405", "2.0") */
    val version: String,
    /** Format of the version (semantic, date-based, numeric) */
    val format: VersionFormat,
    /** Where this version was detected */
    val detectedIn: List<VersionLocation>
)

@Serializable
enum class VersionFormat {
    /** v1, v2, v3 */
    SEMANTIC,
    /** 20210405, 2021-04-05 */
    DATE_BASED,
    /** 1, 2, 3 */
    NUMERIC,
    /** Other format */
    CUSTOM
}

@Serializable
data class VersionLocation(
    /** Class where version was found */
    val classFqn: String,
    /** Method or field name (if applicable) */
    val memberName: String?,
    /** Type of location */
    val locationType: VersionLocationType,
    /** The actual code pattern detected */
    val pattern: String
)

@Serializable
enum class VersionLocationType {
    CLASS_NAME_SUFFIX,
    CHAIN_PREFIX,
    CHAIN_WHEN_CONDITION,
    API_VERSION_CONTEXT_USAGE,
    PATH_BINDING
}

/**
 * Handler with version information.
 */
@Serializable
data class VersionedHandler(
    /** Handler class FQN */
    val handlerFqn: String,
    /** Simple name */
    val simpleName: String,
    /** Detected version */
    val version: String,
    /** How version was determined */
    val versionSource: VersionLocationType,
    /** Routes this handler serves */
    val routes: List<String>
)

/**
 * Summary of API versioning in the project.
 */
@Serializable
data class ApiVersioningSummary(
    /** Primary versioning strategy detected */
    val primaryStrategy: VersioningStrategy,
    /** All strategies detected (if mixed) */
    val detectedStrategies: List<VersioningStrategy>,
    /** All versions found */
    val versions: List<ApiVersion>,
    /** Handlers organized by version */
    val handlersByVersion: Map<String, List<VersionedHandler>>,
    /** Total versioned handlers */
    val versionedHandlerCount: Int,
    /** Handlers without version info */
    val unversionedHandlerCount: Int
)
```

### 8.2 Detection Algorithm

#### ApiVersionDetector (`server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/ApiVersionDetector.kt`)

```kotlin
package codelens.classgraph.ratpack

import codelens.core.model.ClassInfo
import codelens.core.model.ClassSource
import codelens.core.model.ratpack.*

/**
 * Detects API versioning patterns in Ratpack applications.
 *
 * Detection strategies:
 * 1. Class name suffixes: *Handler_v20210405, *Handler_v1
 * 2. ApiVersionContext usage in handler signatures
 * 3. chain.when() conditions checking versions
 * 4. chain.prefix() with version patterns like "/v1/", "/api/v2/"
 */
class ApiVersionDetector(private val classes: Map<String, ClassInfo>) {

    companion object {
        // Patterns for version detection
        private val CLASS_SUFFIX_PATTERN = Regex("_v(\\d+)$|_v(\\d{8})$|V(\\d+)$")
        private val PATH_VERSION_PATTERN = Regex("/v(\\d+)/|/api/v(\\d+)/")
        private val DATE_VERSION_PATTERN = Regex("\\d{8}|\\d{4}-\\d{2}-\\d{2}")
        private val SEMANTIC_VERSION_PATTERN = Regex("v\\d+(\\.\\d+)*")

        private const val API_VERSION_CONTEXT = "ratpack.handling.ApiVersionContext"
        private const val HANDLER_INTERFACE = "ratpack.handling.Handler"
        private const val CHAIN_INTERFACE = "ratpack.handling.Chain"
    }

    /**
     * Analyze the codebase for API versioning patterns.
     */
    fun analyze(): ApiVersioningSummary {
        val versionLocations = mutableListOf<VersionLocation>()
        val versionedHandlers = mutableListOf<VersionedHandler>()
        val detectedStrategies = mutableSetOf<VersioningStrategy>()

        // Find all handlers
        val handlers = findHandlers()

        // Strategy 1: Check class name suffixes
        handlers.forEach { handler ->
            detectClassNameSuffix(handler)?.let { (version, location) ->
                versionLocations.add(location)
                versionedHandlers.add(VersionedHandler(
                    handlerFqn = handler.name.fqn,
                    simpleName = handler.name.simpleName,
                    version = version,
                    versionSource = VersionLocationType.CLASS_NAME_SUFFIX,
                    routes = emptyList() // Populated by RouteAnalyzer
                ))
                detectedStrategies.add(VersioningStrategy.CLASS_SUFFIX)
            }
        }

        // Strategy 2: Check for ApiVersionContext usage
        handlers.forEach { handler ->
            if (usesApiVersionContext(handler)) {
                versionLocations.add(VersionLocation(
                    classFqn = handler.name.fqn,
                    memberName = "handle",
                    locationType = VersionLocationType.API_VERSION_CONTEXT_USAGE,
                    pattern = "ApiVersionContext injection"
                ))
                detectedStrategies.add(VersioningStrategy.CONTEXT_BASED)
            }
        }

        // Strategy 3: Check Action<Chain> classes for version patterns
        val chainActions = findChainActions()
        chainActions.forEach { chainAction ->
            detectChainVersionPatterns(chainAction).forEach { location ->
                versionLocations.add(location)
                when (location.locationType) {
                    VersionLocationType.CHAIN_PREFIX ->
                        detectedStrategies.add(VersioningStrategy.PATH_BASED)
                    VersionLocationType.CHAIN_WHEN_CONDITION ->
                        detectedStrategies.add(VersioningStrategy.HEADER_BASED)
                    else -> {}
                }
            }
        }

        // Build version summary
        val versions = buildVersionList(versionLocations)
        val handlersByVersion = versionedHandlers.groupBy { it.version }

        val primaryStrategy = when {
            detectedStrategies.isEmpty() -> VersioningStrategy.NONE
            detectedStrategies.size == 1 -> detectedStrategies.first()
            else -> VersioningStrategy.MIXED
        }

        return ApiVersioningSummary(
            primaryStrategy = primaryStrategy,
            detectedStrategies = detectedStrategies.toList(),
            versions = versions,
            handlersByVersion = handlersByVersion,
            versionedHandlerCount = versionedHandlers.size,
            unversionedHandlerCount = handlers.size - versionedHandlers.size
        )
    }

    private fun findHandlers(): List<ClassInfo> {
        return classes.values.filter { classInfo ->
            classInfo.source == ClassSource.PROJECT &&
            classInfo.interfaces.contains(HANDLER_INTERFACE)
        }
    }

    private fun findChainActions(): List<ClassInfo> {
        return classes.values.filter { classInfo ->
            classInfo.source == ClassSource.PROJECT &&
            classInfo.interfaces.any { it.startsWith("ratpack.func.Action") } &&
            classInfo.methods.any { method ->
                method.name == "execute" &&
                method.parameters.any { it.type.contains("Chain") }
            }
        }
    }

    private fun detectClassNameSuffix(handler: ClassInfo): Pair<String, VersionLocation>? {
        val match = CLASS_SUFFIX_PATTERN.find(handler.name.simpleName) ?: return null
        val version = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return null

        return version to VersionLocation(
            classFqn = handler.name.fqn,
            memberName = null,
            locationType = VersionLocationType.CLASS_NAME_SUFFIX,
            pattern = handler.name.simpleName
        )
    }

    private fun usesApiVersionContext(handler: ClassInfo): Boolean {
        return handler.methods.any { method ->
            method.name == "handle" &&
            method.parameters.any { param ->
                param.type == API_VERSION_CONTEXT ||
                param.annotations.any { it.type.contains("ApiVersion") }
            }
        } || handler.fields.any { field ->
            field.type == API_VERSION_CONTEXT
        }
    }

    private fun detectChainVersionPatterns(chainAction: ClassInfo): List<VersionLocation> {
        val locations = mutableListOf<VersionLocation>()

        // Look for string constants that match version patterns
        // This is a heuristic - bytecode analysis limitations
        chainAction.fields.forEach { field ->
            if (field.type == "java.lang.String") {
                // Check field name for version hints
                if (field.name.lowercase().contains("version") ||
                    field.name.lowercase().contains("prefix")) {
                    locations.add(VersionLocation(
                        classFqn = chainAction.name.fqn,
                        memberName = field.name,
                        locationType = VersionLocationType.CHAIN_PREFIX,
                        pattern = "String field: ${field.name}"
                    ))
                }
            }
        }

        return locations
    }

    private fun buildVersionList(locations: List<VersionLocation>): List<ApiVersion> {
        return locations
            .groupBy { extractVersionId(it.pattern) }
            .filterKeys { it != null }
            .map { (version, locs) ->
                ApiVersion(
                    version = version!!,
                    format = detectVersionFormat(version),
                    detectedIn = locs
                )
            }
    }

    private fun extractVersionId(pattern: String): String? {
        SEMANTIC_VERSION_PATTERN.find(pattern)?.let { return it.value }
        DATE_VERSION_PATTERN.find(pattern)?.let { return it.value }
        return null
    }

    private fun detectVersionFormat(version: String): VersionFormat {
        return when {
            version.matches(Regex("v\\d+(\\.\\d+)*")) -> VersionFormat.SEMANTIC
            version.matches(Regex("\\d{8}")) -> VersionFormat.DATE_BASED
            version.matches(Regex("\\d+")) -> VersionFormat.NUMERIC
            else -> VersionFormat.CUSTOM
        }
    }
}
```

### 8.3 API Endpoints

#### Routes (`server/app/src/main/kotlin/codelens/server/routes/RatpackRoutes.kt`)

```kotlin
// Add to existing RatpackRoutes.kt

/**
 * GET /api/v1/ratpack/api/versions
 * Get API versioning summary for the project.
 */
get("/ratpack/api/versions") {
    val summary = analysisService.getApiVersioningSummary()
    call.respond(ApiVersioningResponse(summary = summary))
}

/**
 * GET /api/v1/ratpack/api/versions/{version}
 * Get handlers for a specific API version.
 */
get("/ratpack/api/versions/{version}") {
    val version = call.parameters["version"]
        ?: return@get call.respond(HttpStatusCode.BadRequest,
            ErrorResponse(400, "BadRequest", "Version parameter required"))

    val handlers = analysisService.getHandlersForVersion(version)
    call.respond(VersionHandlersResponse(
        version = version,
        handlers = handlers,
        totalCount = handlers.size
    ))
}
```

#### Response Models

```kotlin
@Serializable
data class ApiVersioningResponse(
    val summary: ApiVersioningSummary
)

@Serializable
data class VersionHandlersResponse(
    val version: String,
    val handlers: List<VersionedHandler>,
    val totalCount: Int
)
```

### 8.4 CLI Implementation

#### Commands (`cli/src/codelens_cli/commands/api.py`)

```python
"""API analysis commands."""

from typing import Optional

import typer

from codelens_cli.commands.common import ensure_server_running, get_client, handle_api_errors
from codelens_cli.output import is_tty, print_json

app = typer.Typer(
    name="api",
    help="Analyze API versioning patterns.",
    no_args_is_help=True,
)


@app.command(name="versions")
def list_versions(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show API versioning summary for the project."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_api_versions()

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_version_summary(result)


@app.command(name="show")
def show_version(
    version: str = typer.Argument(help="API version to show (e.g., 'v1', '20210405')"),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show handlers for a specific API version."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_version_handlers(version)

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_version_handlers(result)


def _print_version_summary(result: dict) -> None:
    """Print API versioning summary."""
    from rich.console import Console
    from rich.table import Table

    console = Console()
    summary = result.get("summary", {})

    console.print("\n[bold]API Versioning Summary[/bold]")
    console.print()

    # Strategy info
    strategy = summary.get("primaryStrategy", "NONE")
    strategy_color = {
        "PATH_BASED": "green",
        "HEADER_BASED": "blue",
        "CONTEXT_BASED": "cyan",
        "CLASS_SUFFIX": "yellow",
        "MIXED": "magenta",
        "NONE": "dim",
    }.get(strategy, "white")

    console.print(f"Primary Strategy: [{strategy_color}]{strategy}[/]")

    if summary.get("detectedStrategies"):
        strategies = ", ".join(summary["detectedStrategies"])
        console.print(f"Detected Strategies: {strategies}")

    versioned = summary.get("versionedHandlerCount", 0)
    unversioned = summary.get("unversionedHandlerCount", 0)
    console.print(f"Versioned Handlers: {versioned}")
    console.print(f"Unversioned Handlers: {unversioned}")
    console.print()

    # Version table
    versions = summary.get("versions", [])
    if versions:
        console.print("[bold]Detected Versions[/bold]")
        table = Table(show_header=True, header_style="bold")
        table.add_column("Version")
        table.add_column("Format")
        table.add_column("Locations")

        for v in versions:
            locations = len(v.get("detectedIn", []))
            table.add_row(v["version"], v["format"], str(locations))

        console.print(table)
    else:
        console.print("[yellow]No API versions detected.[/yellow]")

    console.print()


def _print_version_handlers(result: dict) -> None:
    """Print handlers for a specific version."""
    from rich.console import Console
    from rich.table import Table

    console = Console()

    version = result.get("version", "unknown")
    handlers = result.get("handlers", [])

    console.print(f"\n[bold]Handlers for API Version: {version}[/bold]")
    console.print(f"Total: {len(handlers)}")
    console.print()

    if not handlers:
        console.print("[yellow]No handlers found for this version.[/yellow]")
        return

    table = Table(show_header=True, header_style="bold")
    table.add_column("Handler Class")
    table.add_column("Version Source")
    table.add_column("Routes")

    for h in handlers:
        routes = ", ".join(h.get("routes", [])) or "-"
        table.add_row(
            h["simpleName"],
            h["versionSource"],
            routes,
        )

    console.print(table)
    console.print()
```

#### Client Methods (`cli/src/codelens_cli/client.py`)

```python
# Add to CodeLensClient class

def get_api_versions(self) -> dict[str, Any]:
    """Get API versioning summary."""
    return self._get("/api/v1/ratpack/api/versions")

def get_version_handlers(self, version: str) -> dict[str, Any]:
    """Get handlers for a specific API version."""
    return self._get(f"/api/v1/ratpack/api/versions/{version}")
```

---

## Feature 9: Pattern-Based Migration Hints

### 9.1 Data Models

#### Server Models (`server/core/src/main/kotlin/codelens/core/model/ratpack/MigrationHintModels.kt`)

```kotlin
package codelens.core.model.ratpack

import kotlinx.serialization.Serializable

/**
 * Category of migration pattern.
 */
@Serializable
enum class PatternCategory {
    HANDLER,
    PROMISE,
    BLOCKING,
    ROUTING,
    DI,
    TESTING,
    ERROR_HANDLING,
    STREAMING
}

/**
 * Migration effort level.
 */
@Serializable
enum class MigrationEffort {
    /** Direct mapping, mostly mechanical */
    TRIVIAL,
    /** Requires some understanding but straightforward */
    LOW,
    /** Requires design decisions or moderate refactoring */
    MEDIUM,
    /** Complex transformation, architecture changes */
    HIGH,
    /** Requires significant redesign */
    VERY_HIGH
}

/**
 * Target framework for migration.
 */
@Serializable
enum class TargetFramework {
    KOTLIN_SPRING,
    JAVA_SPRING,
    KOTLIN_KTOR,
    MICRONAUT
}

/**
 * A specific Ratpack pattern detected in code.
 */
@Serializable
data class DetectedPattern(
    /** Pattern identifier */
    val patternId: String,
    /** Human-readable name */
    val name: String,
    /** Category of pattern */
    val category: PatternCategory,
    /** Where it was found */
    val location: PatternLocation,
    /** How confident we are in detection (0.0-1.0) */
    val confidence: Double
)

@Serializable
data class PatternLocation(
    /** Class FQN */
    val classFqn: String,
    /** Method name (if applicable) */
    val methodName: String?,
    /** Line number hint (if available from debug info) */
    val lineHint: Int?
)

/**
 * Migration hint for a detected pattern.
 */
@Serializable
data class MigrationHint(
    /** Pattern this hint applies to */
    val patternId: String,
    /** Target framework */
    val targetFramework: TargetFramework,
    /** Short description of the migration */
    val summary: String,
    /** Detailed explanation */
    val description: String,
    /** Migration effort estimate */
    val effort: MigrationEffort,
    /** Example Ratpack code (before) */
    val beforeExample: String,
    /** Example target framework code (after) */
    val afterExample: String,
    /** Additional notes or gotchas */
    val notes: List<String>,
    /** Links to relevant documentation */
    val documentationLinks: List<String>
)

/**
 * Complete migration hints for a class or handler.
 */
@Serializable
data class ClassMigrationHints(
    /** Class being analyzed */
    val classFqn: String,
    /** Patterns detected in this class */
    val detectedPatterns: List<DetectedPattern>,
    /** Hints for each pattern, grouped by target framework */
    val hintsByFramework: Map<TargetFramework, List<MigrationHint>>,
    /** Overall migration effort for this class */
    val overallEffort: MigrationEffort,
    /** Estimated hours to migrate (rough) */
    val estimatedHours: Double
)
```

### 9.2 Pattern Catalog

#### MigrationHintCatalog (`server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/MigrationHintCatalog.kt`)

```kotlin
package codelens.classgraph.ratpack

import codelens.core.model.ratpack.*

/**
 * Catalog of known Ratpack patterns and their migration hints.
 *
 * This is the authoritative source for pattern-to-hint mappings.
 */
object MigrationHintCatalog {

    /**
     * All known pattern definitions.
     */
    val patterns: Map<String, PatternDefinition> = mapOf(
        // === Handler Patterns ===

        "SIMPLE_HANDLER" to PatternDefinition(
            id = "SIMPLE_HANDLER",
            name = "Simple Handler",
            category = PatternCategory.HANDLER,
            description = "Basic Handler implementation with ctx.render()",
            detection = DetectionCriteria(
                implementsInterface = "ratpack.handling.Handler",
                methodCalls = listOf("render", "next")
            )
        ),

        "BLOCKING_GET_HANDLER" to PatternDefinition(
            id = "BLOCKING_GET_HANDLER",
            name = "Blocking.get() in Handler",
            category = PatternCategory.BLOCKING,
            description = "Handler using Blocking.get() for synchronous operations",
            detection = DetectionCriteria(
                implementsInterface = "ratpack.handling.Handler",
                methodCalls = listOf("Blocking.get", "Blocking.op")
            )
        ),

        "PROMISE_CHAIN_HANDLER" to PatternDefinition(
            id = "PROMISE_CHAIN_HANDLER",
            name = "Promise Chain in Handler",
            category = PatternCategory.PROMISE,
            description = "Handler using Promise.then()/map()/flatMap() chains",
            detection = DetectionCriteria(
                implementsInterface = "ratpack.handling.Handler",
                returnTypes = listOf("ratpack.exec.Promise"),
                methodCalls = listOf("then", "map", "flatMap")
            )
        ),

        "CONTEXT_REGISTRY_LOOKUP" to PatternDefinition(
            id = "CONTEXT_REGISTRY_LOOKUP",
            name = "Registry Lookup",
            category = PatternCategory.DI,
            description = "Using ctx.get() for dependency injection",
            detection = DetectionCriteria(
                methodCalls = listOf("Context.get", "Registry.get")
            )
        ),

        // === Routing Patterns ===

        "CHAIN_PREFIX" to PatternDefinition(
            id = "CHAIN_PREFIX",
            name = "Chain Prefix Routing",
            category = PatternCategory.ROUTING,
            description = "Using chain.prefix() for route grouping",
            detection = DetectionCriteria(
                methodCalls = listOf("Chain.prefix")
            )
        ),

        "CHAIN_PATH_BINDING" to PatternDefinition(
            id = "CHAIN_PATH_BINDING",
            name = "Path Parameter Binding",
            category = PatternCategory.ROUTING,
            description = "Using :param syntax for path parameters",
            detection = DetectionCriteria(
                methodCalls = listOf("Chain.get", "Chain.post", "Chain.path"),
                patternInStrings = listOf(":\\w+")
            )
        ),

        // === Promise Patterns ===

        "PROMISE_FLATMAP" to PatternDefinition(
            id = "PROMISE_FLATMAP",
            name = "Promise FlatMap Composition",
            category = PatternCategory.PROMISE,
            description = "Composing async operations with flatMap()",
            detection = DetectionCriteria(
                returnTypes = listOf("ratpack.exec.Promise"),
                methodCalls = listOf("flatMap")
            )
        ),

        "PROMISE_CACHE" to PatternDefinition(
            id = "PROMISE_CACHE",
            name = "Promise Caching",
            category = PatternCategory.PROMISE,
            description = "Using Promise.cache() for memoization",
            detection = DetectionCriteria(
                methodCalls = listOf("Promise.cache")
            )
        ),

        // === Error Handling ===

        "ERROR_HANDLER" to PatternDefinition(
            id = "ERROR_HANDLER",
            name = "Custom Error Handler",
            category = PatternCategory.ERROR_HANDLING,
            description = "Implementing ServerErrorHandler for error handling",
            detection = DetectionCriteria(
                implementsInterface = "ratpack.error.ServerErrorHandler"
            )
        ),

        "PROMISE_ON_ERROR" to PatternDefinition(
            id = "PROMISE_ON_ERROR",
            name = "Promise Error Handling",
            category = PatternCategory.PROMISE,
            description = "Using onError() for promise error handling",
            detection = DetectionCriteria(
                methodCalls = listOf("onError", "mapError")
            )
        )
    )

    /**
     * Migration hints for each pattern + target framework combination.
     */
    val hints: Map<Pair<String, TargetFramework>, MigrationHint> = buildMap {

        // === SIMPLE_HANDLER ===

        put("SIMPLE_HANDLER" to TargetFramework.KOTLIN_SPRING, MigrationHint(
            patternId = "SIMPLE_HANDLER",
            targetFramework = TargetFramework.KOTLIN_SPRING,
            summary = "Convert to @RestController method",
            description = """
                Simple Ratpack handlers map directly to Spring @RestController methods.
                The handler's render() call becomes the method return value.
            """.trimIndent(),
            effort = MigrationEffort.TRIVIAL,
            beforeExample = """
                class UserHandler : Handler {
                    override fun handle(ctx: Context) {
                        val userId = ctx.pathTokens["id"]
                        val user = userService.findById(userId)
                        ctx.render(Jackson.json(user))
                    }
                }
            """.trimIndent(),
            afterExample = """
                @RestController
                @RequestMapping("/users")
                class UserController(private val userService: UserService) {

                    @GetMapping("/{id}")
                    fun getUser(@PathVariable id: String): User {
                        return userService.findById(id)
                    }
                }
            """.trimIndent(),
            notes = listOf(
                "Jackson serialization is automatic in Spring",
                "Path tokens become @PathVariable parameters",
                "Context injection is replaced by constructor injection"
            ),
            documentationLinks = listOf(
                "https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html"
            )
        ))

        // === BLOCKING_GET_HANDLER ===

        put("BLOCKING_GET_HANDLER" to TargetFramework.KOTLIN_SPRING, MigrationHint(
            patternId = "BLOCKING_GET_HANDLER",
            targetFramework = TargetFramework.KOTLIN_SPRING,
            summary = "Remove Blocking.get() wrapper - Spring MVC is blocking by default",
            description = """
                Ratpack's Blocking.get() moves code to a blocking thread pool.
                Spring MVC methods already run on a blocking thread, so the wrapper
                is unnecessary. For WebFlux, consider keeping async patterns.
            """.trimIndent(),
            effort = MigrationEffort.LOW,
            beforeExample = """
                class UserHandler : Handler {
                    override fun handle(ctx: Context) {
                        Blocking.get {
                            userRepository.findById(ctx.pathTokens["id"])
                        }.then { user ->
                            ctx.render(Jackson.json(user))
                        }
                    }
                }
            """.trimIndent(),
            afterExample = """
                @RestController
                class UserController(private val userRepository: UserRepository) {

                    @GetMapping("/users/{id}")
                    fun getUser(@PathVariable id: String): User {
                        // Direct call - no blocking wrapper needed
                        return userRepository.findById(id)
                            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
                    }
                }
            """.trimIndent(),
            notes = listOf(
                "Spring MVC uses a thread-per-request model - blocking is normal",
                "For Spring WebFlux, use Mono.fromCallable() instead",
                "Consider @Async for background tasks"
            ),
            documentationLinks = listOf(
                "https://docs.spring.io/spring-framework/reference/integration/scheduling.html"
            )
        ))

        // === PROMISE_CHAIN_HANDLER ===

        put("PROMISE_CHAIN_HANDLER" to TargetFramework.KOTLIN_SPRING, MigrationHint(
            patternId = "PROMISE_CHAIN_HANDLER",
            targetFramework = TargetFramework.KOTLIN_SPRING,
            summary = "Convert Promise chains to Kotlin coroutines or synchronous code",
            description = """
                Ratpack Promise chains can be converted to Kotlin coroutines with suspend
                functions, or simplified to synchronous code if blocking is acceptable.
            """.trimIndent(),
            effort = MigrationEffort.MEDIUM,
            beforeExample = """
                class OrderHandler : Handler {
                    override fun handle(ctx: Context) {
                        userService.findUser(ctx.pathTokens["userId"])
                            .flatMap { user ->
                                orderService.findOrders(user.id)
                            }
                            .map { orders ->
                                OrderResponse(orders)
                            }
                            .then { response ->
                                ctx.render(Jackson.json(response))
                            }
                    }
                }
            """.trimIndent(),
            afterExample = """
                @RestController
                class OrderController(
                    private val userService: UserService,
                    private val orderService: OrderService
                ) {
                    @GetMapping("/users/{userId}/orders")
                    suspend fun getOrders(@PathVariable userId: String): OrderResponse {
                        val user = userService.findUser(userId)
                        val orders = orderService.findOrders(user.id)
                        return OrderResponse(orders)
                    }
                }
            """.trimIndent(),
            notes = listOf(
                "Add spring-boot-starter-webflux for coroutine support",
                "Each flatMap() becomes a sequential await",
                "Parallel operations use coroutineScope { async { } }"
            ),
            documentationLinks = listOf(
                "https://docs.spring.io/spring-framework/reference/languages/kotlin/coroutines.html"
            )
        ))

        // === CONTEXT_REGISTRY_LOOKUP ===

        put("CONTEXT_REGISTRY_LOOKUP" to TargetFramework.KOTLIN_SPRING, MigrationHint(
            patternId = "CONTEXT_REGISTRY_LOOKUP",
            targetFramework = TargetFramework.KOTLIN_SPRING,
            summary = "Replace Registry lookups with constructor injection",
            description = """
                Ratpack's Registry.get() is replaced by Spring's constructor injection.
                Request-scoped objects use @RequestScope beans.
            """.trimIndent(),
            effort = MigrationEffort.LOW,
            beforeExample = """
                class UserHandler : Handler {
                    override fun handle(ctx: Context) {
                        val userService = ctx.get(UserService::class.java)
                        val currentUser = ctx.get(CurrentUser::class.java)
                        // ...
                    }
                }
            """.trimIndent(),
            afterExample = """
                @RestController
                class UserController(
                    private val userService: UserService  // Constructor injection
                ) {
                    @GetMapping("/users/me")
                    fun getCurrentUser(
                        @AuthenticationPrincipal currentUser: User  // Spring Security
                    ): User {
                        return currentUser
                    }
                }
            """.trimIndent(),
            notes = listOf(
                "Singleton services use constructor injection",
                "Request-scoped data uses method parameters or @RequestScope",
                "Security context uses Spring Security annotations"
            ),
            documentationLinks = listOf(
                "https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html"
            )
        ))

        // === CHAIN_PREFIX ===

        put("CHAIN_PREFIX" to TargetFramework.KOTLIN_SPRING, MigrationHint(
            patternId = "CHAIN_PREFIX",
            targetFramework = TargetFramework.KOTLIN_SPRING,
            summary = "Convert to @RequestMapping on controller class",
            description = """
                Ratpack's chain.prefix() groups routes under a common path.
                In Spring, use @RequestMapping at the class level.
            """.trimIndent(),
            effort = MigrationEffort.TRIVIAL,
            beforeExample = """
                class ApiChain : Action<Chain> {
                    override fun execute(chain: Chain) {
                        chain.prefix("users") { users ->
                            users.get(":id", UserHandler::class.java)
                            users.post(CreateUserHandler::class.java)
                        }
                    }
                }
            """.trimIndent(),
            afterExample = """
                @RestController
                @RequestMapping("/users")
                class UserController {

                    @GetMapping("/{id}")
                    fun getUser(@PathVariable id: String): User { ... }

                    @PostMapping
                    fun createUser(@RequestBody request: CreateUserRequest): User { ... }
                }
            """.trimIndent(),
            notes = listOf(
                "Nested prefixes become nested @RequestMapping values",
                "Consider OpenAPI annotations for documentation"
            ),
            documentationLinks = listOf()
        ))

        // === ERROR_HANDLER ===

        put("ERROR_HANDLER" to TargetFramework.KOTLIN_SPRING, MigrationHint(
            patternId = "ERROR_HANDLER",
            targetFramework = TargetFramework.KOTLIN_SPRING,
            summary = "Convert to @ControllerAdvice with @ExceptionHandler",
            description = """
                Ratpack's ServerErrorHandler becomes Spring's @ControllerAdvice.
                Each exception type gets an @ExceptionHandler method.
            """.trimIndent(),
            effort = MigrationEffort.MEDIUM,
            beforeExample = """
                class AppErrorHandler : ServerErrorHandler {
                    override fun error(ctx: Context, throwable: Throwable) {
                        when (throwable) {
                            is NotFoundException -> ctx.response.status(404)
                            is ValidationException -> ctx.response.status(400)
                            else -> ctx.response.status(500)
                        }
                        ctx.render(Jackson.json(ErrorResponse(throwable.message)))
                    }
                }
            """.trimIndent(),
            afterExample = """
                @ControllerAdvice
                class GlobalExceptionHandler {

                    @ExceptionHandler(NotFoundException::class)
                    fun handleNotFound(ex: NotFoundException): ResponseEntity<ErrorResponse> {
                        return ResponseEntity.status(404)
                            .body(ErrorResponse(ex.message))
                    }

                    @ExceptionHandler(ValidationException::class)
                    fun handleValidation(ex: ValidationException): ResponseEntity<ErrorResponse> {
                        return ResponseEntity.badRequest()
                            .body(ErrorResponse(ex.message))
                    }

                    @ExceptionHandler(Exception::class)
                    fun handleGeneric(ex: Exception): ResponseEntity<ErrorResponse> {
                        return ResponseEntity.internalServerError()
                            .body(ErrorResponse("Internal error"))
                    }
                }
            """.trimIndent(),
            notes = listOf(
                "Order handlers from specific to generic",
                "Consider ProblemDetail for RFC 7807 compliance",
                "Use @ResponseStatus on custom exceptions for simple cases"
            ),
            documentationLinks = listOf(
                "https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-exceptionhandler.html"
            )
        ))
    )

    /**
     * Get hint for a pattern and target framework.
     */
    fun getHint(patternId: String, target: TargetFramework): MigrationHint? {
        return hints[patternId to target]
    }

    /**
     * Get all hints for a target framework.
     */
    fun getHintsForFramework(target: TargetFramework): List<MigrationHint> {
        return hints.filterKeys { it.second == target }.values.toList()
    }
}

/**
 * Definition of a detectable pattern.
 */
data class PatternDefinition(
    val id: String,
    val name: String,
    val category: PatternCategory,
    val description: String,
    val detection: DetectionCriteria
)

/**
 * Criteria for detecting a pattern via bytecode analysis.
 */
data class DetectionCriteria(
    val implementsInterface: String? = null,
    val extendsClass: String? = null,
    val hasAnnotation: String? = null,
    val methodCalls: List<String> = emptyList(),
    val returnTypes: List<String> = emptyList(),
    val fieldTypes: List<String> = emptyList(),
    val patternInStrings: List<String> = emptyList()
)
```

### 9.3 Pattern Detector

#### MigrationHintGenerator (`server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/MigrationHintGenerator.kt`)

```kotlin
package codelens.classgraph.ratpack

import codelens.core.model.ClassInfo
import codelens.core.model.ClassSource
import codelens.core.model.ratpack.*

/**
 * Generates migration hints by detecting patterns in classes.
 */
class MigrationHintGenerator(private val classes: Map<String, ClassInfo>) {

    /**
     * Analyze a class and generate migration hints.
     */
    fun analyzeClass(
        classFqn: String,
        targetFrameworks: List<TargetFramework> = listOf(TargetFramework.KOTLIN_SPRING)
    ): ClassMigrationHints? {
        val classInfo = classes[classFqn] ?: return null

        val detectedPatterns = detectPatterns(classInfo)

        val hintsByFramework = targetFrameworks.associateWith { framework ->
            detectedPatterns.mapNotNull { pattern ->
                MigrationHintCatalog.getHint(pattern.patternId, framework)
            }
        }

        val overallEffort = calculateOverallEffort(hintsByFramework.values.flatten())
        val estimatedHours = estimateHours(detectedPatterns, overallEffort)

        return ClassMigrationHints(
            classFqn = classFqn,
            detectedPatterns = detectedPatterns,
            hintsByFramework = hintsByFramework,
            overallEffort = overallEffort,
            estimatedHours = estimatedHours
        )
    }

    /**
     * Detect all patterns present in a class.
     */
    private fun detectPatterns(classInfo: ClassInfo): List<DetectedPattern> {
        val patterns = mutableListOf<DetectedPattern>()

        for ((patternId, definition) in MigrationHintCatalog.patterns) {
            val confidence = matchPattern(classInfo, definition.detection)
            if (confidence > 0.5) {
                patterns.add(DetectedPattern(
                    patternId = patternId,
                    name = definition.name,
                    category = definition.category,
                    location = PatternLocation(
                        classFqn = classInfo.name.fqn,
                        methodName = null,
                        lineHint = null
                    ),
                    confidence = confidence
                ))
            }
        }

        return patterns
    }

    /**
     * Calculate match confidence for a pattern.
     */
    private fun matchPattern(classInfo: ClassInfo, criteria: DetectionCriteria): Double {
        var matchCount = 0
        var criteriaCount = 0

        // Check interface implementation
        criteria.implementsInterface?.let { iface ->
            criteriaCount++
            if (classInfo.interfaces.contains(iface) ||
                hasTransitiveInterface(classInfo, iface)) {
                matchCount++
            }
        }

        // Check class extension
        criteria.extendsClass?.let { superclass ->
            criteriaCount++
            if (classInfo.superclass == superclass) {
                matchCount++
            }
        }

        // Check annotations
        criteria.hasAnnotation?.let { annotation ->
            criteriaCount++
            if (classInfo.annotations.any { it.type == annotation }) {
                matchCount++
            }
        }

        // Check method calls (via method references in bytecode)
        if (criteria.methodCalls.isNotEmpty()) {
            criteriaCount++
            // Check if any methods reference the target methods
            // This is approximate - bytecode analysis limitations
            val allMethodNames = classInfo.methods.map { it.name }
            if (criteria.methodCalls.any { call ->
                allMethodNames.any { it.contains(call.substringAfterLast(".")) }
            }) {
                matchCount++
            }
        }

        // Check return types
        if (criteria.returnTypes.isNotEmpty()) {
            criteriaCount++
            val methodReturnTypes = classInfo.methods.map { it.returnType }
            if (criteria.returnTypes.any { rt ->
                methodReturnTypes.any { it.contains(rt) }
            }) {
                matchCount++
            }
        }

        // Check field types
        if (criteria.fieldTypes.isNotEmpty()) {
            criteriaCount++
            val fieldTypes = classInfo.fields.map { it.type }
            if (criteria.fieldTypes.any { ft ->
                fieldTypes.any { it.contains(ft) }
            }) {
                matchCount++
            }
        }

        return if (criteriaCount > 0) matchCount.toDouble() / criteriaCount else 0.0
    }

    /**
     * Check if class transitively implements an interface.
     */
    private fun hasTransitiveInterface(classInfo: ClassInfo, interfaceFqn: String): Boolean {
        // Check direct interfaces
        if (classInfo.interfaces.contains(interfaceFqn)) return true

        // Check interfaces of interfaces
        for (iface in classInfo.interfaces) {
            classes[iface]?.let { ifaceInfo ->
                if (hasTransitiveInterface(ifaceInfo, interfaceFqn)) return true
            }
        }

        // Check superclass
        classInfo.superclass?.let { superFqn ->
            classes[superFqn]?.let { superInfo ->
                if (hasTransitiveInterface(superInfo, interfaceFqn)) return true
            }
        }

        return false
    }

    private fun calculateOverallEffort(hints: List<MigrationHint>): MigrationEffort {
        if (hints.isEmpty()) return MigrationEffort.TRIVIAL

        val maxEffort = hints.maxOfOrNull { it.effort.ordinal } ?: 0
        return MigrationEffort.entries[maxEffort]
    }

    private fun estimateHours(patterns: List<DetectedPattern>, effort: MigrationEffort): Double {
        val baseHours = when (effort) {
            MigrationEffort.TRIVIAL -> 0.5
            MigrationEffort.LOW -> 1.0
            MigrationEffort.MEDIUM -> 2.0
            MigrationEffort.HIGH -> 4.0
            MigrationEffort.VERY_HIGH -> 8.0
        }
        // Add time for each pattern
        return baseHours + (patterns.size * 0.25)
    }
}
```

### 9.4 API Endpoints

```kotlin
/**
 * GET /api/v1/ratpack/hints/{classFqn}
 * Get migration hints for a specific class.
 *
 * Query parameters:
 * - target: Target framework (default: KOTLIN_SPRING)
 */
get("/ratpack/hints/{fqn...}") {
    val fqn = getFqnOrRespond() ?: return@get
    val target = call.request.queryParameters["target"]
        ?.let { TargetFramework.valueOf(it.uppercase()) }
        ?: TargetFramework.KOTLIN_SPRING

    val hints = analysisService.getMigrationHints(fqn, listOf(target))
    if (hints != null) {
        call.respond(MigrationHintsResponse(hints = hints))
    } else {
        call.respond(HttpStatusCode.NotFound,
            ErrorResponse(404, "NotFound", "Class not found: $fqn"))
    }
}

/**
 * GET /api/v1/ratpack/hints/catalog
 * Get the full pattern catalog.
 */
get("/ratpack/hints/catalog") {
    val target = call.request.queryParameters["target"]
        ?.let { TargetFramework.valueOf(it.uppercase()) }
        ?: TargetFramework.KOTLIN_SPRING

    val hints = MigrationHintCatalog.getHintsForFramework(target)
    call.respond(HintCatalogResponse(
        targetFramework = target,
        hints = hints
    ))
}
```

---

## Feature 10: Route/Chain Analysis

### 10.1 Data Models

#### Server Models (`server/core/src/main/kotlin/codelens/core/model/ratpack/RouteModels.kt`)

```kotlin
package codelens.core.model.ratpack

import kotlinx.serialization.Serializable

/**
 * HTTP method for a route.
 */
@Serializable
enum class HttpMethod {
    GET, POST, PUT, PATCH, DELETE, OPTIONS, HEAD, ALL
}

/**
 * A single route definition.
 */
@Serializable
data class RouteInfo(
    /** Full path pattern (e.g., "/api/v1/users/:id") */
    val path: String,
    /** HTTP method */
    val method: HttpMethod,
    /** Handler class FQN */
    val handlerFqn: String?,
    /** Handler class simple name */
    val handlerSimpleName: String?,
    /** Path parameters (e.g., ["id"]) */
    val pathParameters: List<String>,
    /** Is this a middleware route (.all())? */
    val isMiddleware: Boolean,
    /** Chain class where this was defined */
    val definedIn: String
)

/**
 * A path parameter extracted from route.
 */
@Serializable
data class PathParameter(
    /** Parameter name (from :name or {name}) */
    val name: String,
    /** Position in path segments */
    val position: Int,
    /** Is this a wildcard/catch-all? */
    val isWildcard: Boolean
)

/**
 * A route tree node for hierarchical representation.
 */
@Serializable
data class RouteTreeNode(
    /** Path segment (e.g., "users", ":id") */
    val segment: String,
    /** Full path to this node */
    val fullPath: String,
    /** Routes defined at this exact path */
    val routes: List<RouteInfo>,
    /** Child nodes */
    val children: List<RouteTreeNode>
)

/**
 * Summary of all routes in the application.
 */
@Serializable
data class RoutingSummary(
    /** All routes as flat list */
    val routes: List<RouteInfo>,
    /** Routes organized as tree */
    val routeTree: RouteTreeNode,
    /** Routes grouped by handler */
    val routesByHandler: Map<String, List<RouteInfo>>,
    /** Total route count */
    val totalRoutes: Int,
    /** Count by HTTP method */
    val routesByMethod: Map<HttpMethod, Int>,
    /** Chain classes analyzed */
    val chainClasses: List<String>,
    /** Middleware routes */
    val middlewareRoutes: List<RouteInfo>
)

/**
 * Spring @RequestMapping equivalent for a route.
 */
@Serializable
data class SpringMappingEquivalent(
    /** Original Ratpack route */
    val ratpackRoute: RouteInfo,
    /** Spring annotation to use */
    val springAnnotation: String,
    /** Suggested method signature */
    val suggestedMethod: String,
    /** Any conversion notes */
    val notes: List<String>
)
```

### 10.2 Route Analyzer

#### RouteAnalyzer (`server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/RouteAnalyzer.kt`)

```kotlin
package codelens.classgraph.ratpack

import codelens.core.model.ClassInfo
import codelens.core.model.ClassSource
import codelens.core.model.ratpack.*

/**
 * Analyzes Ratpack routing chains to extract route definitions.
 *
 * Detection strategies:
 * 1. Find Action<Chain> implementations
 * 2. Look for chain.get(), chain.post(), etc. method calls
 * 3. Extract path patterns from string constants
 * 4. Build route tree from chain.prefix() nesting
 *
 * Limitations:
 * - Cannot see runtime-constructed paths
 * - String interpolation not detectable
 * - Requires compile-time path constants
 */
class RouteAnalyzer(private val classes: Map<String, ClassInfo>) {

    companion object {
        private val CHAIN_METHODS = setOf("get", "post", "put", "patch", "delete", "options", "head", "all", "path", "prefix")
        private val PATH_PARAM_PATTERN = Regex(":([a-zA-Z_][a-zA-Z0-9_]*)")
        private val HANDLER_INTERFACE = "ratpack.handling.Handler"
    }

    /**
     * Analyze all routes in the application.
     */
    fun analyze(): RoutingSummary {
        val routes = mutableListOf<RouteInfo>()
        val chainClasses = mutableListOf<String>()

        // Find all Action<Chain> classes
        val actionChains = findChainActions()
        chainClasses.addAll(actionChains.map { it.name.fqn })

        // Analyze each chain
        actionChains.forEach { chainClass ->
            routes.addAll(analyzeChainClass(chainClass))
        }

        // Build route tree
        val routeTree = buildRouteTree(routes)

        // Group by handler
        val routesByHandler = routes
            .filter { it.handlerFqn != null }
            .groupBy { it.handlerFqn!! }

        // Count by method
        val routesByMethod = routes.groupBy { it.method }
            .mapValues { it.value.size }

        // Find middleware
        val middlewareRoutes = routes.filter { it.isMiddleware }

        return RoutingSummary(
            routes = routes,
            routeTree = routeTree,
            routesByHandler = routesByHandler,
            totalRoutes = routes.size,
            routesByMethod = routesByMethod,
            chainClasses = chainClasses,
            middlewareRoutes = middlewareRoutes
        )
    }

    /**
     * Find all classes that implement Action<Chain>.
     */
    private fun findChainActions(): List<ClassInfo> {
        return classes.values.filter { classInfo ->
            classInfo.source == ClassSource.PROJECT &&
            (classInfo.interfaces.any { it.contains("Action") } ||
             classInfo.superclass?.contains("Action") == true) &&
            classInfo.methods.any { method ->
                method.name == "execute" &&
                method.parameters.any { it.type.contains("Chain") }
            }
        }
    }

    /**
     * Analyze a chain class to extract routes.
     *
     * This uses heuristics since we can't execute the code:
     * 1. Look for string fields that look like paths
     * 2. Check method parameter types for handlers
     * 3. Infer structure from field/method names
     */
    private fun analyzeChainClass(chainClass: ClassInfo): List<RouteInfo> {
        val routes = mutableListOf<RouteInfo>()

        // Look for path-like string constants in fields
        val pathFields = chainClass.fields
            .filter { it.type == "java.lang.String" }
            .filter { field ->
                field.name.lowercase().let {
                    it.contains("path") || it.contains("route") || it.contains("prefix")
                }
            }

        // Look for handler references in methods
        val executeMethod = chainClass.methods.find { it.name == "execute" }

        // Analyze method parameter types for handler classes
        chainClass.methods.forEach { method ->
            method.parameters.forEach { param ->
                if (isHandlerClass(param.type)) {
                    // This parameter is a handler - infer route
                    val handlerInfo = classes[param.type]
                    routes.add(RouteInfo(
                        path = inferPathFromContext(chainClass, method.name),
                        method = inferHttpMethod(method.name),
                        handlerFqn = param.type,
                        handlerSimpleName = handlerInfo?.name?.simpleName ?: param.type.substringAfterLast("."),
                        pathParameters = emptyList(),
                        isMiddleware = method.name == "all",
                        definedIn = chainClass.name.fqn
                    ))
                }
            }
        }

        // Look for inner classes that might be inline handlers
        val innerHandlers = classes.values.filter {
            it.name.fqn.startsWith(chainClass.name.fqn + "$") &&
            it.interfaces.contains(HANDLER_INTERFACE)
        }

        innerHandlers.forEach { handler ->
            routes.add(RouteInfo(
                path = "/<detected>",
                method = HttpMethod.ALL,
                handlerFqn = handler.name.fqn,
                handlerSimpleName = handler.name.simpleName,
                pathParameters = emptyList(),
                isMiddleware = false,
                definedIn = chainClass.name.fqn
            ))
        }

        return routes
    }

    /**
     * Check if a type is a Handler implementation.
     */
    private fun isHandlerClass(typeFqn: String): Boolean {
        val classInfo = classes[typeFqn] ?: return false
        return classInfo.interfaces.contains(HANDLER_INTERFACE) ||
               hasTransitiveInterface(classInfo, HANDLER_INTERFACE)
    }

    private fun hasTransitiveInterface(classInfo: ClassInfo, interfaceFqn: String): Boolean {
        if (classInfo.interfaces.contains(interfaceFqn)) return true

        for (iface in classInfo.interfaces) {
            classes[iface]?.let { ifaceInfo ->
                if (hasTransitiveInterface(ifaceInfo, interfaceFqn)) return true
            }
        }

        classInfo.superclass?.let { superFqn ->
            classes[superFqn]?.let { superInfo ->
                if (hasTransitiveInterface(superInfo, interfaceFqn)) return true
            }
        }

        return false
    }

    /**
     * Infer path from chain class context.
     */
    private fun inferPathFromContext(chainClass: ClassInfo, methodName: String): String {
        // Check class name for hints
        val className = chainClass.name.simpleName.lowercase()

        val basePath = when {
            className.contains("api") -> "/api"
            className.contains("admin") -> "/admin"
            className.contains("user") -> "/users"
            className.contains("order") -> "/orders"
            else -> "/"
        }

        return basePath
    }

    /**
     * Infer HTTP method from method name.
     */
    private fun inferHttpMethod(methodName: String): HttpMethod {
        return when (methodName.lowercase()) {
            "get" -> HttpMethod.GET
            "post" -> HttpMethod.POST
            "put" -> HttpMethod.PUT
            "patch" -> HttpMethod.PATCH
            "delete" -> HttpMethod.DELETE
            "options" -> HttpMethod.OPTIONS
            "head" -> HttpMethod.HEAD
            "all" -> HttpMethod.ALL
            else -> HttpMethod.GET
        }
    }

    /**
     * Build a tree structure from flat routes.
     */
    private fun buildRouteTree(routes: List<RouteInfo>): RouteTreeNode {
        val root = RouteTreeNode(
            segment = "",
            fullPath = "/",
            routes = routes.filter { it.path == "/" },
            children = mutableListOf()
        )

        routes.filter { it.path != "/" }.forEach { route ->
            insertIntoTree(root as RouteTreeNode, route)
        }

        return root
    }

    private fun insertIntoTree(node: RouteTreeNode, route: RouteInfo) {
        val segments = route.path.trim('/').split("/")
        var current = node
        var currentPath = ""

        for (segment in segments) {
            currentPath = "$currentPath/$segment"

            val existingChild = (current.children as MutableList).find { it.segment == segment }
            current = if (existingChild != null) {
                existingChild
            } else {
                val newNode = RouteTreeNode(
                    segment = segment,
                    fullPath = currentPath,
                    routes = mutableListOf(),
                    children = mutableListOf()
                )
                (current.children as MutableList).add(newNode)
                newNode
            }
        }

        (current.routes as MutableList).add(route)
    }

    /**
     * Extract path parameters from a path pattern.
     */
    fun extractPathParameters(path: String): List<PathParameter> {
        val params = mutableListOf<PathParameter>()
        val segments = path.split("/")

        segments.forEachIndexed { index, segment ->
            PATH_PARAM_PATTERN.find(segment)?.let { match ->
                params.add(PathParameter(
                    name = match.groupValues[1],
                    position = index,
                    isWildcard = segment.endsWith("*")
                ))
            }
        }

        return params
    }

    /**
     * Generate Spring @RequestMapping equivalent.
     */
    fun generateSpringMapping(route: RouteInfo): SpringMappingEquivalent {
        val annotation = when (route.method) {
            HttpMethod.GET -> "@GetMapping"
            HttpMethod.POST -> "@PostMapping"
            HttpMethod.PUT -> "@PutMapping"
            HttpMethod.PATCH -> "@PatchMapping"
            HttpMethod.DELETE -> "@DeleteMapping"
            else -> "@RequestMapping"
        }

        // Convert :param to {param}
        val springPath = route.path.replace(PATH_PARAM_PATTERN, "{$1}")

        val pathVars = route.pathParameters.joinToString(", ") {
            "@PathVariable ${it.name}: String"
        }

        val methodName = inferMethodName(route)

        val signature = if (pathVars.isNotEmpty()) {
            "$annotation(\"$springPath\")\nfun $methodName($pathVars): ResponseEntity<Any>"
        } else {
            "$annotation(\"$springPath\")\nfun $methodName(): ResponseEntity<Any>"
        }

        val notes = mutableListOf<String>()
        if (route.isMiddleware) {
            notes.add("This was a middleware (.all()) - consider using a Filter or Interceptor")
        }

        return SpringMappingEquivalent(
            ratpackRoute = route,
            springAnnotation = annotation,
            suggestedMethod = signature,
            notes = notes
        )
    }

    private fun inferMethodName(route: RouteInfo): String {
        val pathParts = route.path.trim('/').split("/")
            .filter { !it.startsWith(":") && !it.startsWith("{") }

        val resource = pathParts.lastOrNull() ?: "resource"

        return when (route.method) {
            HttpMethod.GET -> if (route.pathParameters.isNotEmpty()) "get${resource.capitalize()}" else "list${resource.capitalize()}"
            HttpMethod.POST -> "create${resource.capitalize()}"
            HttpMethod.PUT -> "update${resource.capitalize()}"
            HttpMethod.PATCH -> "patch${resource.capitalize()}"
            HttpMethod.DELETE -> "delete${resource.capitalize()}"
            else -> "handle${resource.capitalize()}"
        }
    }

    private fun String.capitalize(): String =
        replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
```

### 10.3 API Endpoints

```kotlin
/**
 * GET /api/v1/ratpack/routes
 * Get all routes in the application.
 */
get("/ratpack/routes") {
    val summary = analysisService.getRoutingSummary()
    call.respond(RoutingResponse(summary = summary))
}

/**
 * GET /api/v1/ratpack/routes/tree
 * Get routes as a tree structure.
 */
get("/ratpack/routes/tree") {
    val summary = analysisService.getRoutingSummary()
    call.respond(RouteTreeResponse(tree = summary.routeTree))
}

/**
 * GET /api/v1/ratpack/routes/spring
 * Get Spring @RequestMapping equivalents for all routes.
 */
get("/ratpack/routes/spring") {
    val mappings = analysisService.getSpringMappings()
    call.respond(SpringMappingsResponse(mappings = mappings))
}
```

### 10.4 CLI Implementation

#### Commands (`cli/src/codelens_cli/commands/routes.py`)

```python
"""Route analysis commands."""

from typing import Optional

import typer

from codelens_cli.commands.common import ensure_server_running, get_client, handle_api_errors
from codelens_cli.output import is_tty, print_json

app = typer.Typer(
    name="routes",
    help="Analyze Ratpack routing chains.",
    no_args_is_help=True,
)


@app.command(name="list")
def list_routes(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    method: Optional[str] = typer.Option(
        None, "--method", "-m", help="Filter by HTTP method (GET, POST, etc.)"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """List all routes in the application."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_routes()

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_route_list(result, method_filter=method)


@app.command(name="tree")
def show_tree(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show routes as a tree structure."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_route_tree()

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_route_tree(result)


@app.command(name="spring")
def spring_mappings(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Generate Spring @RequestMapping equivalents for routes."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_spring_mappings()

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_spring_mappings(result)


def _print_route_list(result: dict, method_filter: Optional[str] = None) -> None:
    """Print routes as a table."""
    from rich.console import Console
    from rich.table import Table

    console = Console()
    summary = result.get("summary", {})
    routes = summary.get("routes", [])

    if method_filter:
        routes = [r for r in routes if r["method"] == method_filter.upper()]

    console.print(f"\n[bold]Routes[/bold] ({len(routes)} total)")
    console.print()

    if not routes:
        console.print("[yellow]No routes found.[/yellow]")
        return

    table = Table(show_header=True, header_style="bold")
    table.add_column("Method", style="cyan", width=8)
    table.add_column("Path")
    table.add_column("Handler")
    table.add_column("Middleware", justify="center")

    method_colors = {
        "GET": "green",
        "POST": "yellow",
        "PUT": "blue",
        "PATCH": "magenta",
        "DELETE": "red",
        "ALL": "dim",
    }

    for route in routes:
        method = route["method"]
        color = method_colors.get(method, "white")
        middleware = "[dim]Yes[/]" if route.get("isMiddleware") else ""

        table.add_row(
            f"[{color}]{method}[/]",
            route["path"],
            route.get("handlerSimpleName") or "-",
            middleware,
        )

    console.print(table)

    # Print summary
    by_method = summary.get("routesByMethod", {})
    method_summary = ", ".join(f"{m}: {c}" for m, c in by_method.items())
    console.print(f"\n[dim]By method: {method_summary}[/dim]")
    console.print()


def _print_route_tree(result: dict) -> None:
    """Print routes as a tree."""
    from rich.console import Console
    from rich.tree import Tree

    console = Console()
    tree_data = result.get("tree", {})

    console.print("\n[bold]Route Tree[/bold]")
    console.print()

    tree = Tree("[bold]/[/bold]")
    _build_tree_node(tree, tree_data)
    console.print(tree)
    console.print()


def _build_tree_node(parent, node_data: dict) -> None:
    """Recursively build tree visualization."""
    from rich.tree import Tree

    for child in node_data.get("children", []):
        segment = child.get("segment", "")
        routes = child.get("routes", [])

        # Build label
        if routes:
            methods = ", ".join(r["method"] for r in routes)
            label = f"[cyan]{segment}[/] [{methods}]"
        else:
            label = f"[dim]{segment}[/]"

        child_tree = parent.add(label)
        _build_tree_node(child_tree, child)


def _print_spring_mappings(result: dict) -> None:
    """Print Spring mapping equivalents."""
    from rich.console import Console
    from rich.panel import Panel
    from rich.syntax import Syntax

    console = Console()
    mappings = result.get("mappings", [])

    console.print("\n[bold]Spring @RequestMapping Equivalents[/bold]")
    console.print()

    if not mappings:
        console.print("[yellow]No routes to convert.[/yellow]")
        return

    for mapping in mappings:
        route = mapping.get("ratpackRoute", {})
        console.print(f"[bold]{route['method']} {route['path']}[/bold]")

        code = mapping.get("suggestedMethod", "")
        syntax = Syntax(code, "kotlin", theme="monokai", line_numbers=False)
        console.print(Panel(syntax, border_style="dim"))

        for note in mapping.get("notes", []):
            console.print(f"  [yellow]Note:[/] {note}")

        console.print()
```

---

## Feature 11: Anti-pattern Detection

### 11.1 Data Models

#### Server Models (`server/core/src/main/kotlin/codelens/core/model/ratpack/AntiPatternModels.kt`)

```kotlin
package codelens.core.model.ratpack

import kotlinx.serialization.Serializable

/**
 * Anti-pattern identifier.
 */
@Serializable
enum class AntiPatternType {
    /** JDBC calls without Blocking.get() wrapper */
    BLOCKING_JDBC,
    /** Thread.sleep() calls in handlers */
    THREAD_SLEEP,
    /** Synchronous file I/O in handlers */
    SYNCHRONOUS_FILE_IO,
    /** Promise created but never subscribed */
    UNSUBSCRIBED_PROMISE,
    /** Blocking HTTP client calls */
    BLOCKING_HTTP_CLIENT,
    /** Direct System.out/err usage */
    CONSOLE_LOGGING,
    /** Catching and swallowing exceptions */
    SWALLOWED_EXCEPTION,
    /** Large object in request thread */
    MEMORY_PRESSURE,
    /** Unbounded collection in handler */
    UNBOUNDED_COLLECTION
}

/**
 * Severity level for anti-patterns.
 */
@Serializable
enum class AntiPatternSeverity {
    /** Informational - might be intentional */
    INFO,
    /** Warning - should review */
    WARNING,
    /** Error - likely a bug */
    ERROR,
    /** Critical - will cause problems */
    CRITICAL
}

/**
 * A detected anti-pattern instance.
 */
@Serializable
data class AntiPatternInstance(
    /** Anti-pattern type */
    val type: AntiPatternType,
    /** Severity */
    val severity: AntiPatternSeverity,
    /** Class where detected */
    val classFqn: String,
    /** Method where detected (if applicable) */
    val methodName: String?,
    /** Detection confidence (0.0-1.0) */
    val confidence: Double,
    /** Why this was flagged */
    val reason: String,
    /** How to fix it */
    val recommendation: String,
    /** Example fix */
    val fixExample: String?
)

/**
 * Definition of an anti-pattern for detection.
 */
data class AntiPatternDefinition(
    val type: AntiPatternType,
    val name: String,
    val description: String,
    val severity: AntiPatternSeverity,
    val detection: AntiPatternDetection,
    val recommendation: String,
    val fixExample: String
)

/**
 * How to detect an anti-pattern.
 */
data class AntiPatternDetection(
    /** Types that indicate the pattern (method references) */
    val indicatorTypes: List<String> = emptyList(),
    /** Method call patterns to look for */
    val methodPatterns: List<String> = emptyList(),
    /** Field types that indicate the pattern */
    val fieldTypes: List<String> = emptyList(),
    /** Required context (e.g., must be in Handler) */
    val requiredContext: String? = null,
    /** Negation - presence of these means NOT an anti-pattern */
    val negationPatterns: List<String> = emptyList()
)

/**
 * Summary of all anti-patterns found.
 */
@Serializable
data class AntiPatternSummary(
    /** All detected instances */
    val instances: List<AntiPatternInstance>,
    /** Count by type */
    val countByType: Map<AntiPatternType, Int>,
    /** Count by severity */
    val countBySeverity: Map<AntiPatternSeverity, Int>,
    /** Classes with most issues */
    val worstOffenders: List<ClassAntiPatternCount>,
    /** Total count */
    val totalCount: Int
)

@Serializable
data class ClassAntiPatternCount(
    val classFqn: String,
    val count: Int,
    val criticalCount: Int,
    val errorCount: Int
)
```

### 11.2 Anti-pattern Detector

#### AntiPatternDetector (`server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/AntiPatternDetector.kt`)

```kotlin
package codelens.classgraph.ratpack

import codelens.core.model.ClassInfo
import codelens.core.model.ClassSource
import codelens.core.model.ratpack.*

/**
 * Detects common anti-patterns in Ratpack applications.
 */
class AntiPatternDetector(private val classes: Map<String, ClassInfo>) {

    companion object {
        private const val HANDLER_INTERFACE = "ratpack.handling.Handler"

        /**
         * Anti-pattern definitions catalog.
         */
        val ANTI_PATTERNS: List<AntiPatternDefinition> = listOf(
            AntiPatternDefinition(
                type = AntiPatternType.BLOCKING_JDBC,
                name = "Blocking JDBC without Blocking.get()",
                description = "JDBC calls in handlers should be wrapped in Blocking.get() to avoid blocking the event loop",
                severity = AntiPatternSeverity.CRITICAL,
                detection = AntiPatternDetection(
                    indicatorTypes = listOf(
                        "java.sql.Connection",
                        "java.sql.Statement",
                        "java.sql.PreparedStatement",
                        "java.sql.ResultSet",
                        "javax.sql.DataSource"
                    ),
                    methodPatterns = listOf(
                        "executeQuery",
                        "executeUpdate",
                        "execute",
                        "getConnection"
                    ),
                    requiredContext = HANDLER_INTERFACE,
                    negationPatterns = listOf("Blocking.get", "Blocking.op")
                ),
                recommendation = "Wrap JDBC calls in Blocking.get { } or use an async database driver",
                fixExample = """
                    // Before (anti-pattern):
                    override fun handle(ctx: Context) {
                        val result = dataSource.connection.use { conn ->
                            conn.prepareStatement("SELECT * FROM users").executeQuery()
                        }
                        ctx.render(result)
                    }

                    // After (correct):
                    override fun handle(ctx: Context) {
                        Blocking.get {
                            dataSource.connection.use { conn ->
                                conn.prepareStatement("SELECT * FROM users").executeQuery()
                            }
                        }.then { result ->
                            ctx.render(result)
                        }
                    }
                """.trimIndent()
            ),

            AntiPatternDefinition(
                type = AntiPatternType.THREAD_SLEEP,
                name = "Thread.sleep() in Handler",
                description = "Thread.sleep() blocks the event loop thread and degrades performance",
                severity = AntiPatternSeverity.ERROR,
                detection = AntiPatternDetection(
                    methodPatterns = listOf("Thread.sleep", "sleep"),
                    requiredContext = HANDLER_INTERFACE
                ),
                recommendation = "Use Promise.delay() or Execution.sleep() instead",
                fixExample = """
                    // Before (anti-pattern):
                    Thread.sleep(1000)

                    // After (correct):
                    Execution.sleep(Duration.ofSeconds(1)).then {
                        // continue after delay
                    }
                """.trimIndent()
            ),

            AntiPatternDefinition(
                type = AntiPatternType.SYNCHRONOUS_FILE_IO,
                name = "Synchronous File I/O",
                description = "File operations block the event loop and should be in Blocking.get()",
                severity = AntiPatternSeverity.WARNING,
                detection = AntiPatternDetection(
                    indicatorTypes = listOf(
                        "java.io.FileInputStream",
                        "java.io.FileOutputStream",
                        "java.io.FileReader",
                        "java.io.FileWriter",
                        "java.nio.file.Files"
                    ),
                    methodPatterns = listOf(
                        "readAllBytes",
                        "readAllLines",
                        "write",
                        "copy",
                        "move",
                        "delete"
                    ),
                    requiredContext = HANDLER_INTERFACE,
                    negationPatterns = listOf("Blocking.get", "Blocking.op")
                ),
                recommendation = "Wrap file I/O in Blocking.get { } or use async file operations",
                fixExample = """
                    // Before (anti-pattern):
                    val content = Files.readAllBytes(path)

                    // After (correct):
                    Blocking.get {
                        Files.readAllBytes(path)
                    }.then { content ->
                        ctx.render(content)
                    }
                """.trimIndent()
            ),

            AntiPatternDefinition(
                type = AntiPatternType.BLOCKING_HTTP_CLIENT,
                name = "Blocking HTTP Client",
                description = "Synchronous HTTP clients block the event loop",
                severity = AntiPatternSeverity.ERROR,
                detection = AntiPatternDetection(
                    indicatorTypes = listOf(
                        "java.net.HttpURLConnection",
                        "java.net.URL",
                        "org.apache.http.client.HttpClient",
                        "okhttp3.OkHttpClient"
                    ),
                    methodPatterns = listOf(
                        "openConnection",
                        "execute",
                        "newCall"
                    ),
                    requiredContext = HANDLER_INTERFACE,
                    negationPatterns = listOf("Blocking.get", "HttpClient")  // Ratpack's HttpClient is async
                ),
                recommendation = "Use Ratpack's HttpClient or wrap in Blocking.get()",
                fixExample = """
                    // Before (anti-pattern):
                    val response = URL("http://api.example.com/data").readText()

                    // After (correct with Ratpack HttpClient):
                    ctx.get(HttpClient::class.java)
                        .get(URI.create("http://api.example.com/data"))
                        .then { response ->
                            ctx.render(response.body.text)
                        }
                """.trimIndent()
            ),

            AntiPatternDefinition(
                type = AntiPatternType.CONSOLE_LOGGING,
                name = "Console Logging",
                description = "Direct System.out/err usage instead of proper logging",
                severity = AntiPatternSeverity.INFO,
                detection = AntiPatternDetection(
                    methodPatterns = listOf(
                        "System.out.print",
                        "System.err.print",
                        "println"
                    )
                ),
                recommendation = "Use SLF4J logging instead of System.out/err",
                fixExample = """
                    // Before (anti-pattern):
                    System.out.println("Processing request")

                    // After (correct):
                    private val logger = LoggerFactory.getLogger(MyHandler::class.java)
                    logger.info("Processing request")
                """.trimIndent()
            ),

            AntiPatternDefinition(
                type = AntiPatternType.SWALLOWED_EXCEPTION,
                name = "Swallowed Exception",
                description = "Catching exceptions without logging or rethrowing",
                severity = AntiPatternSeverity.WARNING,
                detection = AntiPatternDetection(
                    // This is hard to detect from bytecode alone
                    // Look for try-catch with empty catch blocks
                    methodPatterns = listOf()
                ),
                recommendation = "Log exceptions or rethrow them; don't silently ignore",
                fixExample = """
                    // Before (anti-pattern):
                    try {
                        doSomething()
                    } catch (e: Exception) {
                        // empty or just e.printStackTrace()
                    }

                    // After (correct):
                    try {
                        doSomething()
                    } catch (e: Exception) {
                        logger.error("Failed to do something", e)
                        throw e  // or handle appropriately
                    }
                """.trimIndent()
            )
        )
    }

    /**
     * Scan all handlers for anti-patterns.
     */
    fun analyze(): AntiPatternSummary {
        val instances = mutableListOf<AntiPatternInstance>()

        // Find all handlers
        val handlers = classes.values.filter { classInfo ->
            classInfo.source == ClassSource.PROJECT &&
            (classInfo.interfaces.contains(HANDLER_INTERFACE) ||
             hasTransitiveInterface(classInfo, HANDLER_INTERFACE))
        }

        // Check each handler against each anti-pattern
        handlers.forEach { handler ->
            ANTI_PATTERNS.forEach { antiPattern ->
                detectAntiPattern(handler, antiPattern)?.let { instance ->
                    instances.add(instance)
                }
            }
        }

        // Also check non-handler classes for general issues
        classes.values
            .filter { it.source == ClassSource.PROJECT }
            .filter { !handlers.contains(it) }
            .forEach { classInfo ->
                ANTI_PATTERNS
                    .filter { it.detection.requiredContext == null }
                    .forEach { antiPattern ->
                        detectAntiPattern(classInfo, antiPattern)?.let { instance ->
                            instances.add(instance)
                        }
                    }
            }

        // Build summary
        val countByType = instances.groupBy { it.type }.mapValues { it.value.size }
        val countBySeverity = instances.groupBy { it.severity }.mapValues { it.value.size }

        val worstOffenders = instances
            .groupBy { it.classFqn }
            .map { (classFqn, classInstances) ->
                ClassAntiPatternCount(
                    classFqn = classFqn,
                    count = classInstances.size,
                    criticalCount = classInstances.count { it.severity == AntiPatternSeverity.CRITICAL },
                    errorCount = classInstances.count { it.severity == AntiPatternSeverity.ERROR }
                )
            }
            .sortedByDescending { it.criticalCount * 100 + it.errorCount * 10 + it.count }
            .take(10)

        return AntiPatternSummary(
            instances = instances,
            countByType = countByType,
            countBySeverity = countBySeverity,
            worstOffenders = worstOffenders,
            totalCount = instances.size
        )
    }

    /**
     * Detect if a class exhibits an anti-pattern.
     */
    private fun detectAntiPattern(
        classInfo: ClassInfo,
        antiPattern: AntiPatternDefinition
    ): AntiPatternInstance? {
        val detection = antiPattern.detection
        var indicatorScore = 0.0
        var indicatorCount = 0
        val foundIndicators = mutableListOf<String>()

        // Check indicator types in fields
        if (detection.indicatorTypes.isNotEmpty()) {
            indicatorCount++
            val fieldTypes = classInfo.fields.map { it.type }
            val matchingTypes = detection.indicatorTypes.filter { indicator ->
                fieldTypes.any { it.contains(indicator) }
            }
            if (matchingTypes.isNotEmpty()) {
                indicatorScore += 1.0
                foundIndicators.addAll(matchingTypes)
            }
        }

        // Check method patterns - look for method names or references
        if (detection.methodPatterns.isNotEmpty()) {
            indicatorCount++
            val methodNames = classInfo.methods.map { it.name }
            val returnTypes = classInfo.methods.map { it.returnType }
            val paramTypes = classInfo.methods.flatMap { m -> m.parameters.map { it.type } }

            val allTypeRefs = (returnTypes + paramTypes + classInfo.fields.map { it.type })

            val matchingPatterns = detection.methodPatterns.filter { pattern ->
                methodNames.any { it.contains(pattern) } ||
                allTypeRefs.any { it.contains(pattern) }
            }
            if (matchingPatterns.isNotEmpty()) {
                indicatorScore += 1.0
                foundIndicators.addAll(matchingPatterns)
            }
        }

        // Check negation patterns - if present, NOT an anti-pattern
        if (detection.negationPatterns.isNotEmpty() && foundIndicators.isNotEmpty()) {
            val methodNames = classInfo.methods.map { it.name }
            val returnTypes = classInfo.methods.map { it.returnType }

            val hasNegation = detection.negationPatterns.any { negation ->
                methodNames.any { it.contains(negation) } ||
                returnTypes.any { it.contains(negation) }
            }

            if (hasNegation) {
                return null  // Negation present, not an anti-pattern
            }
        }

        // Calculate confidence
        val confidence = if (indicatorCount > 0) indicatorScore / indicatorCount else 0.0

        if (confidence < 0.5) {
            return null
        }

        return AntiPatternInstance(
            type = antiPattern.type,
            severity = antiPattern.severity,
            classFqn = classInfo.name.fqn,
            methodName = null,  // Would need more detailed analysis
            confidence = confidence,
            reason = "Found indicators: ${foundIndicators.joinToString(", ")}",
            recommendation = antiPattern.recommendation,
            fixExample = antiPattern.fixExample
        )
    }

    private fun hasTransitiveInterface(classInfo: ClassInfo, interfaceFqn: String): Boolean {
        if (classInfo.interfaces.contains(interfaceFqn)) return true

        for (iface in classInfo.interfaces) {
            classes[iface]?.let { ifaceInfo ->
                if (hasTransitiveInterface(ifaceInfo, interfaceFqn)) return true
            }
        }

        classInfo.superclass?.let { superFqn ->
            classes[superFqn]?.let { superInfo ->
                if (hasTransitiveInterface(superInfo, interfaceFqn)) return true
            }
        }

        return false
    }
}
```

### 11.3 API Endpoints

```kotlin
/**
 * GET /api/v1/ratpack/antipatterns
 * Get all detected anti-patterns.
 *
 * Query parameters:
 * - severity: Filter by severity (INFO, WARNING, ERROR, CRITICAL)
 * - type: Filter by anti-pattern type
 */
get("/ratpack/antipatterns") {
    val severityFilter = call.request.queryParameters["severity"]
        ?.let { AntiPatternSeverity.valueOf(it.uppercase()) }
    val typeFilter = call.request.queryParameters["type"]
        ?.let { AntiPatternType.valueOf(it.uppercase()) }

    var summary = analysisService.getAntiPatternSummary()

    // Apply filters
    if (severityFilter != null || typeFilter != null) {
        val filteredInstances = summary.instances.filter { instance ->
            (severityFilter == null || instance.severity == severityFilter) &&
            (typeFilter == null || instance.type == typeFilter)
        }
        summary = summary.copy(
            instances = filteredInstances,
            totalCount = filteredInstances.size
        )
    }

    call.respond(AntiPatternResponse(summary = summary))
}

/**
 * GET /api/v1/ratpack/antipatterns/{classFqn}
 * Get anti-patterns for a specific class.
 */
get("/ratpack/antipatterns/{fqn...}") {
    val fqn = getFqnOrRespond() ?: return@get

    val summary = analysisService.getAntiPatternSummary()
    val classInstances = summary.instances.filter { it.classFqn == fqn }

    call.respond(ClassAntiPatternsResponse(
        classFqn = fqn,
        instances = classInstances,
        totalCount = classInstances.size
    ))
}
```

### 11.4 CLI Implementation

#### Commands (`cli/src/codelens_cli/commands/antipatterns.py`)

```python
"""Anti-pattern detection commands."""

from typing import Optional

import typer

from codelens_cli.commands.common import ensure_server_running, get_client, handle_api_errors
from codelens_cli.output import is_tty, print_json

app = typer.Typer(
    name="antipatterns",
    help="Detect code anti-patterns.",
    no_args_is_help=True,
)


@app.command(name="scan")
def scan_antipatterns(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    severity: Optional[str] = typer.Option(
        None, "--severity", "-s", help="Filter by severity (INFO, WARNING, ERROR, CRITICAL)"
    ),
    type_filter: Optional[str] = typer.Option(
        None, "--type", "-t", help="Filter by anti-pattern type"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Scan for anti-patterns in the codebase."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_antipatterns(severity=severity, type_filter=type_filter)

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_antipattern_summary(result)


@app.command(name="show")
def show_class_antipatterns(
    fqn: str = typer.Argument(help="Fully qualified class name"),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show anti-patterns for a specific class."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_class_antipatterns(fqn)

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_class_antipatterns(result)


def _print_antipattern_summary(result: dict) -> None:
    """Print anti-pattern summary."""
    from rich.console import Console
    from rich.table import Table
    from rich.panel import Panel

    console = Console()
    summary = result.get("summary", {})

    console.print("\n[bold]Anti-Pattern Analysis[/bold]")
    console.print()

    total = summary.get("totalCount", 0)
    if total == 0:
        console.print("[green]No anti-patterns detected![/green]")
        return

    # Severity breakdown
    by_severity = summary.get("countBySeverity", {})
    severity_colors = {
        "CRITICAL": "red bold",
        "ERROR": "red",
        "WARNING": "yellow",
        "INFO": "dim",
    }

    console.print(f"[bold]Total Issues: {total}[/bold]")
    for sev, count in by_severity.items():
        color = severity_colors.get(sev, "white")
        console.print(f"  [{color}]{sev}: {count}[/]")
    console.print()

    # Issues table
    instances = summary.get("instances", [])
    if instances:
        table = Table(show_header=True, header_style="bold")
        table.add_column("Severity", width=10)
        table.add_column("Type")
        table.add_column("Class")
        table.add_column("Confidence", justify="right")

        for instance in instances[:20]:  # Show first 20
            severity = instance["severity"]
            color = severity_colors.get(severity, "white")
            confidence = f"{instance['confidence']*100:.0f}%"

            table.add_row(
                f"[{color}]{severity}[/]",
                instance["type"],
                instance["classFqn"].split(".")[-1],
                confidence,
            )

        console.print(table)

        if len(instances) > 20:
            console.print(f"\n[dim]... and {len(instances) - 20} more[/dim]")

    # Worst offenders
    offenders = summary.get("worstOffenders", [])
    if offenders:
        console.print("\n[bold]Worst Offenders[/bold]")
        for off in offenders[:5]:
            critical = off.get("criticalCount", 0)
            error = off.get("errorCount", 0)
            total_count = off.get("count", 0)

            parts = []
            if critical > 0:
                parts.append(f"[red]{critical} critical[/]")
            if error > 0:
                parts.append(f"[yellow]{error} errors[/]")
            parts.append(f"{total_count} total")

            console.print(f"  {off['classFqn'].split('.')[-1]}: {', '.join(parts)}")

    console.print()


def _print_class_antipatterns(result: dict) -> None:
    """Print anti-patterns for a specific class."""
    from rich.console import Console
    from rich.panel import Panel
    from rich.syntax import Syntax

    console = Console()

    fqn = result.get("classFqn", "")
    instances = result.get("instances", [])

    console.print(f"\n[bold]Anti-patterns in {fqn}[/bold]")
    console.print()

    if not instances:
        console.print("[green]No anti-patterns detected in this class.[/green]")
        return

    severity_colors = {
        "CRITICAL": "red bold",
        "ERROR": "red",
        "WARNING": "yellow",
        "INFO": "dim",
    }

    for instance in instances:
        severity = instance["severity"]
        color = severity_colors.get(severity, "white")

        console.print(f"[{color}]{severity}[/] - [bold]{instance['type']}[/bold]")
        console.print(f"  Reason: {instance['reason']}")
        console.print(f"  [cyan]Recommendation:[/] {instance['recommendation']}")

        if instance.get("fixExample"):
            syntax = Syntax(
                instance["fixExample"],
                "kotlin",
                theme="monokai",
                line_numbers=False,
            )
            console.print(Panel(syntax, title="Fix Example", border_style="dim"))

        console.print()
```

---

## Files to Create/Modify

### New Files (Server)

| File | Description |
|------|-------------|
| `server/core/src/main/kotlin/codelens/core/model/ratpack/ApiVersionModels.kt` | API versioning data models |
| `server/core/src/main/kotlin/codelens/core/model/ratpack/MigrationHintModels.kt` | Migration hint data models |
| `server/core/src/main/kotlin/codelens/core/model/ratpack/RouteModels.kt` | Route analysis data models |
| `server/core/src/main/kotlin/codelens/core/model/ratpack/AntiPatternModels.kt` | Anti-pattern data models |
| `server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/ApiVersionDetector.kt` | Version detection logic |
| `server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/MigrationHintCatalog.kt` | Pattern-to-hint catalog |
| `server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/MigrationHintGenerator.kt` | Hint generation logic |
| `server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/RouteAnalyzer.kt` | Route analysis logic |
| `server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/AntiPatternDetector.kt` | Anti-pattern detection |

### New Files (CLI)

| File | Description |
|------|-------------|
| `cli/src/codelens_cli/commands/api.py` | API versioning commands |
| `cli/src/codelens_cli/commands/routes.py` | Route analysis commands |
| `cli/src/codelens_cli/commands/antipatterns.py` | Anti-pattern commands |

### Modified Files

| File | Changes |
|------|---------|
| `server/app/src/main/kotlin/codelens/server/routes/RatpackRoutes.kt` | Add new endpoints |
| `server/app/src/main/kotlin/codelens/server/services/AnalysisService.kt` | Integrate new analyzers |
| `cli/src/codelens_cli/main.py` | Register new command groups |
| `cli/src/codelens_cli/client.py` | Add new API methods |
| `cli/src/codelens_cli/models.py` | Add new response models |

---

## Testing Strategy

### Unit Tests

1. **ApiVersionDetector**: Test each detection strategy with fixture classes
2. **MigrationHintGenerator**: Test pattern matching against known patterns
3. **RouteAnalyzer**: Test path parsing and tree building
4. **AntiPatternDetector**: Test each anti-pattern detection

### Integration Tests

1. Create test fixtures in `test-fixtures/sample-ratpack-app/`:
   - Versioned handlers with date suffixes
   - ApiVersionContext usage examples
   - Chain definitions with various routes
   - Classes with intentional anti-patterns

2. Test full analysis pipeline against fixtures

### Manual Testing

- Test against moonracer (multiple API versions)
- Test against pumbaa (complex routing)
- Verify hint accuracy

---

## Acceptance Criteria

### Functional
- [ ] `codelens api versions` detects versioning strategy
- [ ] Migration hints are accurate and actionable
- [ ] `codelens routes tree` shows route structure
- [ ] `codelens antipatterns scan` finds blocking issues

### Quality
- [ ] All features have unit tests
- [ ] Integration tests against test fixtures
- [ ] Manual verification against real projects

---

## Key Insights & Takeaways

*Update during implementation.*

### Technical Insights
-

### Pattern Discoveries
-

### Limitations Discovered
-

---

## Deviations Log

| Date | Original Plan | Actual Implementation | Reason |
|------|---------------|----------------------|--------|
| | | | |

---

## Blockers & Issues

| Issue | Status | Resolution |
|-------|--------|------------|
| | | |

---

## Notes for Next Phase

-
