# Phase 2B: Source & Integration Context

**Status**: Not Started
**Prerequisite**: Phase 2A complete
**Target**: Provide LLMs with source code and integration context
**Features**: 5-7 (Source Code Retrieval, External Service Detection, Registry Access Analysis)

---

## Overview

Phase 2B focuses on giving LLMs the context they need to generate accurate migration code. While Phase 2A tells us *what* needs migration, Phase 2B provides the *actual code* and identifies integration points that need special attention.

**Success Criteria**:
- Can retrieve source code for any analyzed class
- Identifies all external service integrations (HTTP, DB, Queue)
- Maps Registry access patterns for DI conversion

---

## Feature 5: Source Code Retrieval

### 5.1 Source Resolution Algorithm

#### FQN to File Path Mapping

The algorithm to resolve a fully qualified name (FQN) to a source file:

```
1. Parse FQN: "com.example.handlers.UserHandler"
   - Package: "com.example.handlers"
   - Class name: "UserHandler"
   - Expected path segment: "com/example/handlers/UserHandler"

2. Handle nested classes:
   - FQN: "com.example.Outer$Inner"
   - Strip inner class: "com.example.Outer"
   - Path segment: "com/example/Outer"

3. For each source root in order:
   a. Try: {sourceRoot}/{pathSegment}.java
   b. Try: {sourceRoot}/{pathSegment}.kt

4. Return first existing file, or null if not found
```

#### Handling Multiple Source Roots

Source roots are ordered by priority:
1. Main source sets (src/main/java, src/main/kotlin)
2. Test source sets (src/test/java, src/test/kotlin)
3. Generated sources (build/generated/sources)

For multi-module projects, each module contributes its own source roots.

```kotlin
// Gradle init script additions to capture source roots
allprojects {
    if (project.plugins.hasPlugin('java') || project.plugins.hasPlugin('java-library')) {
        project.sourceSets.each { sourceSet ->
            sourceSet.java.srcDirs.each { dir ->
                if (dir.exists()) {
                    rootProject.ext.codelensSourceRoots << [
                        type: 'java',
                        sourceSet: sourceSet.name,
                        module: project.path,
                        path: dir.absolutePath
                    ]
                }
            }
            // Also check for kotlin plugin
            try {
                sourceSet.kotlin.srcDirs.each { dir ->
                    if (dir.exists()) {
                        rootProject.ext.codelensSourceRoots << [
                            type: 'kotlin',
                            sourceSet: sourceSet.name,
                            module: project.path,
                            path: dir.absolutePath
                        ]
                    }
                }
            } catch (Exception e) {
                // Kotlin plugin not applied
            }
        }
    }
}
```

#### Method Extraction Using Line Numbers

ClassGraph provides line number information from bytecode debug info:

```kotlin
// In ClassGraphProviderImpl, enhance MethodInfo
data class MethodInfo(
    // ... existing fields ...
    val startLine: Int? = null,  // From bytecode debug info
    val endLine: Int? = null     // Estimated from next method or class end
)

// Extraction from ClassGraph:
val methodInfo = cgMethod.let { method ->
    // ClassGraph exposes minLineNum from Code attribute
    val startLine = method.minLineNum.takeIf { it > 0 }
    MethodInfo(
        // ... existing fields ...
        startLine = startLine
    )
}
```

Method source extraction algorithm:
```
1. Get method's startLine from bytecode
2. If no line info, fall back to text search for method signature
3. Find method end by:
   a. Next method's startLine - 1
   b. Or search for matching closing brace (accounting for nesting)
4. Extract lines [startLine, endLine] from source file
```

### 5.2 Data Models (Server - Kotlin)

```kotlin
// server/core/src/main/kotlin/codelens/core/model/source/SourceModels.kt

package codelens.core.model.source

import kotlinx.serialization.Serializable

/**
 * Represents a source root directory in the project.
 */
@Serializable
data class SourceRoot(
    /** Absolute path to the source root directory */
    val path: String,
    /** Source language: "java" or "kotlin" */
    val language: String,
    /** Source set name: "main", "test", etc. */
    val sourceSet: String,
    /** Module path in multi-module project (e.g., ":server:app") */
    val module: String
)

/**
 * Language type for source files.
 */
@Serializable
enum class SourceLanguage {
    JAVA,
    KOTLIN,
    UNKNOWN
}

/**
 * Complete source code information for a class.
 */
@Serializable
data class SourceInfo(
    /** Fully qualified class name */
    val fqn: String,
    /** Absolute path to the source file */
    val filePath: String,
    /** Source language */
    val language: SourceLanguage,
    /** Full source code content */
    val content: String,
    /** Total number of lines */
    val lineCount: Int,
    /** Module this source belongs to (for multi-module projects) */
    val module: String?
)

/**
 * Source code for a specific method.
 */
@Serializable
data class MethodSourceInfo(
    /** Fully qualified class name */
    val classFqn: String,
    /** Method name */
    val methodName: String,
    /** Method signature for disambiguation */
    val signature: String,
    /** Source code of the method */
    val content: String,
    /** Starting line number in the file (1-based) */
    val startLine: Int,
    /** Ending line number in the file (1-based) */
    val endLine: Int,
    /** Context lines before the method (if requested) */
    val contextBefore: String?,
    /** Context lines after the method (if requested) */
    val contextAfter: String?
)

/**
 * Error when source cannot be resolved.
 */
@Serializable
data class SourceResolutionError(
    /** The FQN that could not be resolved */
    val fqn: String,
    /** Reason for failure */
    val reason: SourceResolutionErrorReason,
    /** Human-readable message */
    val message: String
)

@Serializable
enum class SourceResolutionErrorReason {
    /** Class is from a library, source not available */
    LIBRARY_CLASS,
    /** Class is from JDK, source not available */
    JDK_CLASS,
    /** Source file not found in any source root */
    FILE_NOT_FOUND,
    /** Class not found in scan results */
    CLASS_NOT_FOUND,
    /** Method not found in class */
    METHOD_NOT_FOUND
}
```

### 5.3 SourceResolver Implementation

```kotlin
// server/classgraph/src/main/kotlin/codelens/classgraph/source/SourceResolver.kt

package codelens.classgraph.source

import codelens.core.model.ClassInfo
import codelens.core.model.ClassSource
import codelens.core.model.source.*
import java.io.File

/**
 * Resolves source code for analyzed classes.
 */
class SourceResolver(
    private val sourceRoots: List<SourceRoot>,
    private val classes: Map<String, ClassInfo>
) {

    /**
     * Resolves source code for a class by FQN.
     *
     * @param fqn Fully qualified class name
     * @return SourceInfo or error
     */
    fun resolveClass(fqn: String): Result<SourceInfo> {
        // 1. Check if class exists and is a project class
        val classInfo = classes[fqn]
            ?: return Result.failure(SourceResolutionException(
                fqn, SourceResolutionErrorReason.CLASS_NOT_FOUND,
                "Class not found in scan results: $fqn"
            ))

        if (classInfo.source == ClassSource.LIBRARY) {
            return Result.failure(SourceResolutionException(
                fqn, SourceResolutionErrorReason.LIBRARY_CLASS,
                "Source not available for library class: $fqn"
            ))
        }

        if (classInfo.source == ClassSource.JDK) {
            return Result.failure(SourceResolutionException(
                fqn, SourceResolutionErrorReason.JDK_CLASS,
                "Source not available for JDK class: $fqn"
            ))
        }

        // 2. Convert FQN to path segment
        val pathSegment = fqnToPathSegment(fqn)

        // 3. Search source roots
        for (sourceRoot in sourceRoots) {
            val extension = if (sourceRoot.language == "kotlin") ".kt" else ".java"
            val sourceFile = File(sourceRoot.path, "$pathSegment$extension")

            if (sourceFile.exists() && sourceFile.isFile) {
                val content = sourceFile.readText()
                return Result.success(SourceInfo(
                    fqn = fqn,
                    filePath = sourceFile.absolutePath,
                    language = if (sourceRoot.language == "kotlin")
                        SourceLanguage.KOTLIN else SourceLanguage.JAVA,
                    content = content,
                    lineCount = content.lines().size,
                    module = sourceRoot.module.takeIf { it != ":" }
                ))
            }
        }

        return Result.failure(SourceResolutionException(
            fqn, SourceResolutionErrorReason.FILE_NOT_FOUND,
            "Source file not found for class: $fqn. Searched ${sourceRoots.size} source roots."
        ))
    }

    /**
     * Resolves source code for a specific method.
     *
     * @param fqn Fully qualified class name
     * @param methodName Method name
     * @param parameterTypes Optional list of parameter types for disambiguation
     * @param contextLines Number of context lines to include before/after
     * @return MethodSourceInfo or error
     */
    fun resolveMethod(
        fqn: String,
        methodName: String,
        parameterTypes: List<String>? = null,
        contextLines: Int = 0
    ): Result<MethodSourceInfo> {
        // 1. Get class source
        val sourceResult = resolveClass(fqn)
        if (sourceResult.isFailure) {
            return Result.failure(sourceResult.exceptionOrNull()!!)
        }
        val sourceInfo = sourceResult.getOrThrow()

        // 2. Find method in class info
        val classInfo = classes[fqn]!!
        val method = classInfo.methods.find { m ->
            m.name == methodName && (parameterTypes == null ||
                m.parameters.map { it.type } == parameterTypes)
        } ?: return Result.failure(SourceResolutionException(
            fqn, SourceResolutionErrorReason.METHOD_NOT_FOUND,
            "Method not found: $methodName in class $fqn"
        ))

        // 3. Extract method source using line numbers or text search
        val lines = sourceInfo.content.lines()
        val methodExtractor = MethodExtractor(lines, sourceInfo.language)

        val extraction = if (method.startLine != null && method.startLine > 0) {
            methodExtractor.extractByLineNumber(method.startLine, methodName)
        } else {
            methodExtractor.extractBySignature(methodName, method.parameters.map { it.type })
        }

        if (extraction == null) {
            return Result.failure(SourceResolutionException(
                fqn, SourceResolutionErrorReason.METHOD_NOT_FOUND,
                "Could not extract method source for: $methodName"
            ))
        }

        // 4. Add context if requested
        val contextBefore = if (contextLines > 0) {
            lines.subList(
                maxOf(0, extraction.startLine - 1 - contextLines),
                extraction.startLine - 1
            ).joinToString("\n")
        } else null

        val contextAfter = if (contextLines > 0) {
            lines.subList(
                extraction.endLine,
                minOf(lines.size, extraction.endLine + contextLines)
            ).joinToString("\n")
        } else null

        return Result.success(MethodSourceInfo(
            classFqn = fqn,
            methodName = methodName,
            signature = buildMethodSignature(method),
            content = extraction.content,
            startLine = extraction.startLine,
            endLine = extraction.endLine,
            contextBefore = contextBefore,
            contextAfter = contextAfter
        ))
    }

    /**
     * Converts FQN to file path segment, handling nested classes.
     */
    private fun fqnToPathSegment(fqn: String): String {
        // Handle nested classes: com.example.Outer$Inner -> com/example/Outer
        val outerClass = fqn.substringBefore('$')
        return outerClass.replace('.', '/')
    }

    private fun buildMethodSignature(method: MethodInfo): String {
        val params = method.parameters.joinToString(", ") { "${it.type} ${it.name}" }
        return "${method.name}($params): ${method.returnType}"
    }
}

/**
 * Extracts method source from file content.
 */
class MethodExtractor(
    private val lines: List<String>,
    private val language: SourceLanguage
) {
    data class Extraction(
        val content: String,
        val startLine: Int,  // 1-based
        val endLine: Int     // 1-based
    )

    fun extractByLineNumber(startLine: Int, methodName: String): Extraction? {
        if (startLine < 1 || startLine > lines.size) return null

        // Find method end by tracking brace nesting
        var braceCount = 0
        var foundStart = false
        var endLine = startLine

        for (i in (startLine - 1) until lines.size) {
            val line = lines[i]
            for (char in line) {
                when (char) {
                    '{' -> {
                        braceCount++
                        foundStart = true
                    }
                    '}' -> {
                        braceCount--
                        if (foundStart && braceCount == 0) {
                            endLine = i + 1
                            return Extraction(
                                content = lines.subList(startLine - 1, endLine).joinToString("\n"),
                                startLine = startLine,
                                endLine = endLine
                            )
                        }
                    }
                }
            }
        }

        return null
    }

    fun extractBySignature(methodName: String, paramTypes: List<String>): Extraction? {
        // Search for method declaration pattern
        val methodPattern = when (language) {
            SourceLanguage.KOTLIN -> Regex("""fun\s+$methodName\s*\(""")
            SourceLanguage.JAVA -> Regex("""\b$methodName\s*\(""")
            else -> return null
        }

        for (i in lines.indices) {
            if (methodPattern.containsMatchIn(lines[i])) {
                // Verify parameter types match (simplified check)
                return extractByLineNumber(i + 1, methodName)
            }
        }

        return null
    }
}

class SourceResolutionException(
    val fqn: String,
    val reason: SourceResolutionErrorReason,
    message: String
) : Exception(message)
```

### 5.4 API Endpoints

#### GET /api/v1/source/{fqn...}

Retrieve full source code for a class.

**Request:**
```
GET /api/v1/source/com.example.handlers.UserHandler
```

**Response (200 OK):**
```json
{
  "fqn": "com.example.handlers.UserHandler",
  "filePath": "/path/to/project/src/main/kotlin/com/example/handlers/UserHandler.kt",
  "language": "KOTLIN",
  "content": "package com.example.handlers\n\nimport ratpack.handling.Context\nimport ratpack.handling.Handler\n\nclass UserHandler : Handler {\n    override fun handle(ctx: Context) {\n        val userId = ctx.pathTokens[\"id\"]\n        val userService = ctx.get(UserService::class.java)\n        // ...\n    }\n}",
  "lineCount": 15,
  "module": ":server:app"
}
```

**Response (404 Not Found - Library Class):**
```json
{
  "error": true,
  "code": 404,
  "type": "SourceNotAvailable",
  "message": "Source not available for library class: ratpack.handling.Handler",
  "reason": "LIBRARY_CLASS"
}
```

#### GET /api/v1/source/{fqn...}/method/{methodName}

Retrieve source code for a specific method.

**Request:**
```
GET /api/v1/source/com.example.handlers.UserHandler/method/handle?context=3
```

**Query Parameters:**
- `context` (optional, default: 0): Number of lines to include before/after the method
- `paramTypes` (optional): Comma-separated parameter types for disambiguation (e.g., `ratpack.handling.Context`)

**Response (200 OK):**
```json
{
  "classFqn": "com.example.handlers.UserHandler",
  "methodName": "handle",
  "signature": "handle(ctx: Context): void",
  "content": "    override fun handle(ctx: Context) {\n        val userId = ctx.pathTokens[\"id\"]\n        val userService = ctx.get(UserService::class.java)\n        ctx.render(userService.getUser(userId))\n    }",
  "startLine": 8,
  "endLine": 12,
  "contextBefore": "\nclass UserHandler : Handler {\n",
  "contextAfter": "\n}\n"
}
```

### 5.5 Server Routes Implementation

```kotlin
// server/app/src/main/kotlin/codelens/server/routes/SourceRoutes.kt

package codelens.server.routes

import codelens.core.model.ErrorResponse
import codelens.core.model.source.*
import codelens.server.services.AnalysisService
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.sourceRoutes(analysisService: AnalysisService) {
    route("/api/v1/source") {
        /**
         * GET /api/v1/source/{fqn...}
         * Get source code for a class.
         */
        get("/{fqn...}") {
            val fqn = call.parameters.getAll("fqn")?.joinToString(".") ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(
                    code = 400, type = "BadRequest", message = "Class FQN is required"
                ))
                return@get
            }

            // Check if this is a method request (contains /method/)
            if (fqn.contains(".method.")) {
                // This is actually routed to the method endpoint below
                return@get
            }

            val result = analysisService.getSource(fqn)
            result.fold(
                onSuccess = { sourceInfo ->
                    call.respond(sourceInfo)
                },
                onFailure = { exception ->
                    when (exception) {
                        is SourceResolutionException -> {
                            val statusCode = when (exception.reason) {
                                SourceResolutionErrorReason.CLASS_NOT_FOUND -> HttpStatusCode.NotFound
                                SourceResolutionErrorReason.FILE_NOT_FOUND -> HttpStatusCode.NotFound
                                SourceResolutionErrorReason.LIBRARY_CLASS,
                                SourceResolutionErrorReason.JDK_CLASS -> HttpStatusCode.NotFound
                                else -> HttpStatusCode.InternalServerError
                            }
                            call.respond(statusCode, SourceResolutionError(
                                fqn = exception.fqn,
                                reason = exception.reason,
                                message = exception.message ?: "Unknown error"
                            ))
                        }
                        else -> {
                            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(
                                code = 500, type = "InternalError", message = exception.message ?: "Unknown error"
                            ))
                        }
                    }
                }
            )
        }

        /**
         * GET /api/v1/source/{fqn...}/method/{methodName}
         * Get source code for a specific method.
         */
        get("/{fqn...}/method/{methodName}") {
            val pathParts = call.parameters.getAll("fqn") ?: emptyList()
            val fqn = pathParts.joinToString(".")
            val methodName = call.parameters["methodName"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(
                    code = 400, type = "BadRequest", message = "Method name is required"
                ))
                return@get
            }

            val contextLines = call.request.queryParameters["context"]?.toIntOrNull() ?: 0
            val paramTypes = call.request.queryParameters["paramTypes"]?.split(",")

            val result = analysisService.getMethodSource(fqn, methodName, paramTypes, contextLines)
            result.fold(
                onSuccess = { methodSource ->
                    call.respond(methodSource)
                },
                onFailure = { exception ->
                    when (exception) {
                        is SourceResolutionException -> {
                            call.respond(HttpStatusCode.NotFound, SourceResolutionError(
                                fqn = exception.fqn,
                                reason = exception.reason,
                                message = exception.message ?: "Unknown error"
                            ))
                        }
                        else -> {
                            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(
                                code = 500, type = "InternalError", message = exception.message ?: "Unknown error"
                            ))
                        }
                    }
                }
            )
        }
    }
}
```

### 5.6 CLI Implementation

```python
# cli/src/codelens_cli/commands/source.py

"""Source code retrieval commands."""

from typing import Optional

import typer
from rich.console import Console
from rich.syntax import Syntax
from rich.panel import Panel

from codelens_cli.commands.common import ensure_server_running, get_client, handle_api_errors
from codelens_cli.models import SourceInfo, MethodSourceInfo, SourceResolutionError
from codelens_cli.output import is_tty, print_json

app = typer.Typer(
    name="source",
    help="Retrieve source code for analyzed classes.",
    no_args_is_help=True,
)

console = Console()


@app.command(name="show")
def show_source(
    fqn: str = typer.Argument(help="Fully qualified class name"),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    line_numbers: bool = typer.Option(
        True, "--line-numbers/--no-line-numbers", "-n/-N",
        help="Show line numbers"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show source code for a class."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_source(fqn)

        if json_output or not is_tty():
            print_json(result)
        else:
            # Check for error response
            if "reason" in result:
                error = SourceResolutionError.model_validate(result)
                console.print(f"[red]Error:[/red] {error.message}")
                console.print(f"[dim]Reason: {error.reason}[/dim]")
                raise typer.Exit(1)

            source = SourceInfo.model_validate(result)

            # Determine lexer based on language
            lexer = "kotlin" if source.language == "KOTLIN" else "java"

            # Display with syntax highlighting
            syntax = Syntax(
                source.content,
                lexer,
                theme="monokai",
                line_numbers=line_numbers,
                word_wrap=False,
            )

            title = f"{source.fqn} ({source.language.lower()})"
            if source.module:
                title += f" [{source.module}]"

            panel = Panel(
                syntax,
                title=title,
                subtitle=f"{source.line_count} lines | {source.file_path}",
                border_style="blue",
            )
            console.print(panel)


@app.command(name="method")
def show_method(
    fqn: str = typer.Argument(help="Fully qualified class name"),
    method: str = typer.Argument(help="Method name"),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    context: int = typer.Option(
        0, "--context", "-c", help="Lines of context before/after method"
    ),
    param_types: Optional[str] = typer.Option(
        None, "--params", help="Parameter types for disambiguation (comma-separated)"
    ),
    line_numbers: bool = typer.Option(
        True, "--line-numbers/--no-line-numbers", "-n/-N",
        help="Show line numbers"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show source code for a specific method."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_method_source(
            fqn, method,
            context=context,
            param_types=param_types.split(",") if param_types else None
        )

        if json_output or not is_tty():
            print_json(result)
        else:
            if "reason" in result:
                error = SourceResolutionError.model_validate(result)
                console.print(f"[red]Error:[/red] {error.message}")
                raise typer.Exit(1)

            method_source = MethodSourceInfo.model_validate(result)

            # Build display content with context
            display_content = ""
            start_line = method_source.start_line

            if method_source.context_before:
                display_content += method_source.context_before + "\n"
                # Adjust start line for context
                context_lines = method_source.context_before.count("\n") + 1
                start_line -= context_lines

            display_content += method_source.content

            if method_source.context_after:
                display_content += "\n" + method_source.context_after

            # Detect language from class FQN (heuristic)
            lexer = "kotlin"  # Default to Kotlin for this project

            syntax = Syntax(
                display_content,
                lexer,
                theme="monokai",
                line_numbers=line_numbers,
                start_line=max(1, start_line),
                word_wrap=False,
            )

            panel = Panel(
                syntax,
                title=f"{method_source.class_fqn}.{method_source.method_name}",
                subtitle=f"Lines {method_source.start_line}-{method_source.end_line} | {method_source.signature}",
                border_style="green",
            )
            console.print(panel)
```

### 5.7 CLI Client Methods

```python
# Add to cli/src/codelens_cli/client.py

def get_source(self, fqn: str) -> dict[str, Any]:
    """Get source code for a class."""
    return self._get(f"/api/v1/source/{fqn}")

def get_method_source(
    self,
    fqn: str,
    method_name: str,
    context: int = 0,
    param_types: list[str] | None = None,
) -> dict[str, Any]:
    """Get source code for a specific method."""
    params: dict[str, Any] = {}
    if context > 0:
        params["context"] = context
    if param_types:
        params["paramTypes"] = ",".join(param_types)
    return self._get(f"/api/v1/source/{fqn}/method/{method_name}", params=params or None)
```

### 5.8 CLI Models

```python
# Add to cli/src/codelens_cli/models.py

class SourceLanguage(str, Enum):
    """Source file language."""
    JAVA = "JAVA"
    KOTLIN = "KOTLIN"
    UNKNOWN = "UNKNOWN"


class SourceResolutionErrorReason(str, Enum):
    """Reason for source resolution failure."""
    LIBRARY_CLASS = "LIBRARY_CLASS"
    JDK_CLASS = "JDK_CLASS"
    FILE_NOT_FOUND = "FILE_NOT_FOUND"
    CLASS_NOT_FOUND = "CLASS_NOT_FOUND"
    METHOD_NOT_FOUND = "METHOD_NOT_FOUND"


class SourceInfo(BaseModel):
    """Source code for a class."""
    fqn: str
    file_path: str = Field(alias="filePath")
    language: SourceLanguage
    content: str
    line_count: int = Field(alias="lineCount")
    module: Optional[str] = None

    class Config:
        populate_by_name = True


class MethodSourceInfo(BaseModel):
    """Source code for a method."""
    class_fqn: str = Field(alias="classFqn")
    method_name: str = Field(alias="methodName")
    signature: str
    content: str
    start_line: int = Field(alias="startLine")
    end_line: int = Field(alias="endLine")
    context_before: Optional[str] = Field(None, alias="contextBefore")
    context_after: Optional[str] = Field(None, alias="contextAfter")

    class Config:
        populate_by_name = True


class SourceResolutionError(BaseModel):
    """Error when source cannot be resolved."""
    fqn: str
    reason: SourceResolutionErrorReason
    message: str
```

---

## Feature 6: External Service Integration Detection

### 6.1 Integration Detection Strategy

The IntegrationDetector scans all project classes for field types, method parameters, return types, and method calls that indicate usage of external services.

### 6.2 Integration Type Categories and Detection Patterns

```kotlin
/**
 * Categories of external service integrations.
 */
enum class IntegrationType {
    HTTP_CLIENT,      // Outbound HTTP calls
    DATABASE,         // Database access (SQL, NoSQL)
    MESSAGE_QUEUE,    // Message queues (SQS, SNS, RabbitMQ, Kafka)
    CACHE,            // Caching systems (Redis, Memcached)
    GRPC,             // gRPC clients
    SOAP,             // SOAP web services
    FILE_STORAGE,     // Cloud file storage (S3)
    OTHER             // Other external services
}
```

### 6.3 Detection Patterns by Type

#### HTTP_CLIENT
```kotlin
val HTTP_CLIENT_PATTERNS = listOf(
    // Ratpack
    "ratpack.http.client.HttpClient",
    "ratpack.http.client.ReceivedResponse",

    // Apache HttpClient
    "org.apache.http.client.HttpClient",
    "org.apache.http.impl.client.CloseableHttpClient",
    "org.apache.http.client.methods.HttpGet",
    "org.apache.http.client.methods.HttpPost",

    // OkHttp
    "okhttp3.OkHttpClient",
    "okhttp3.Request",
    "okhttp3.Response",

    // Java 11+ HttpClient
    "java.net.http.HttpClient",
    "java.net.http.HttpRequest",
    "java.net.http.HttpResponse",

    // Spring WebClient
    "org.springframework.web.reactive.function.client.WebClient",

    // Retrofit
    "retrofit2.Retrofit",
    "retrofit2.Call",

    // Feign
    "feign.Feign",
    "feign.Client"
)
```

#### DATABASE (DynamoDB)
```kotlin
val DYNAMODB_PATTERNS = listOf(
    // AWS SDK v1
    "com.amazonaws.services.dynamodbv2.AmazonDynamoDB",
    "com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient",
    "com.amazonaws.services.dynamodbv2.document.DynamoDB",
    "com.amazonaws.services.dynamodbv2.document.Table",
    "com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper",

    // AWS SDK v2
    "software.amazon.awssdk.services.dynamodb.DynamoDbClient",
    "software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient",
    "software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient",
    "software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable"
)
```

#### DATABASE (JDBC/SQL)
```kotlin
val JDBC_PATTERNS = listOf(
    "java.sql.Connection",
    "java.sql.PreparedStatement",
    "java.sql.ResultSet",
    "java.sql.Statement",
    "javax.sql.DataSource",

    // HikariCP
    "com.zaxxer.hikari.HikariDataSource",

    // JOOQ
    "org.jooq.DSLContext",
    "org.jooq.Query",

    // JPA/Hibernate
    "javax.persistence.EntityManager",
    "org.hibernate.Session",
    "org.hibernate.SessionFactory"
)
```

#### MESSAGE_QUEUE (SQS)
```kotlin
val SQS_PATTERNS = listOf(
    // AWS SDK v1
    "com.amazonaws.services.sqs.AmazonSQS",
    "com.amazonaws.services.sqs.AmazonSQSClient",
    "com.amazonaws.services.sqs.model.SendMessageRequest",
    "com.amazonaws.services.sqs.model.ReceiveMessageRequest",

    // AWS SDK v2
    "software.amazon.awssdk.services.sqs.SqsClient",
    "software.amazon.awssdk.services.sqs.SqsAsyncClient",
    "software.amazon.awssdk.services.sqs.model.SendMessageRequest"
)
```

#### MESSAGE_QUEUE (SNS)
```kotlin
val SNS_PATTERNS = listOf(
    // AWS SDK v1
    "com.amazonaws.services.sns.AmazonSNS",
    "com.amazonaws.services.sns.AmazonSNSClient",

    // AWS SDK v2
    "software.amazon.awssdk.services.sns.SnsClient",
    "software.amazon.awssdk.services.sns.SnsAsyncClient"
)
```

#### MESSAGE_QUEUE (Kafka)
```kotlin
val KAFKA_PATTERNS = listOf(
    "org.apache.kafka.clients.producer.KafkaProducer",
    "org.apache.kafka.clients.producer.Producer",
    "org.apache.kafka.clients.consumer.KafkaConsumer",
    "org.apache.kafka.clients.consumer.Consumer"
)
```

#### CACHE (Redis)
```kotlin
val REDIS_PATTERNS = listOf(
    // Lettuce
    "io.lettuce.core.RedisClient",
    "io.lettuce.core.api.StatefulRedisConnection",
    "io.lettuce.core.api.sync.RedisCommands",
    "io.lettuce.core.api.async.RedisAsyncCommands",

    // Jedis
    "redis.clients.jedis.Jedis",
    "redis.clients.jedis.JedisPool",
    "redis.clients.jedis.JedisCluster"
)
```

#### GRPC
```kotlin
val GRPC_PATTERNS = listOf(
    "io.grpc.ManagedChannel",
    "io.grpc.stub.AbstractStub",
    "io.grpc.stub.AbstractBlockingStub",
    "io.grpc.stub.AbstractAsyncStub"
)
```

#### FILE_STORAGE (S3)
```kotlin
val S3_PATTERNS = listOf(
    // AWS SDK v1
    "com.amazonaws.services.s3.AmazonS3",
    "com.amazonaws.services.s3.AmazonS3Client",

    // AWS SDK v2
    "software.amazon.awssdk.services.s3.S3Client",
    "software.amazon.awssdk.services.s3.S3AsyncClient"
)
```

### 6.4 Data Models

```kotlin
// server/core/src/main/kotlin/codelens/core/model/ratpack/IntegrationModels.kt

package codelens.core.model.ratpack

import codelens.core.model.ClassSource
import kotlinx.serialization.Serializable

/**
 * Type of external service integration.
 */
@Serializable
enum class IntegrationType {
    HTTP_CLIENT,
    DATABASE,
    MESSAGE_QUEUE,
    CACHE,
    GRPC,
    SOAP,
    FILE_STORAGE,
    OTHER
}

/**
 * Sub-type providing more detail about the integration.
 */
@Serializable
enum class IntegrationSubType {
    // HTTP
    RATPACK_HTTP_CLIENT,
    APACHE_HTTP_CLIENT,
    OKHTTP,
    JAVA_HTTP_CLIENT,
    SPRING_WEBCLIENT,
    RETROFIT,
    FEIGN,

    // Database
    DYNAMODB,
    JDBC,
    JPA,
    JOOQ,

    // Message Queue
    SQS,
    SNS,
    KAFKA,
    RABBITMQ,

    // Cache
    REDIS_LETTUCE,
    REDIS_JEDIS,
    MEMCACHED,

    // gRPC
    GRPC_CLIENT,

    // Storage
    S3,

    // Other
    UNKNOWN
}

/**
 * Where the integration was detected in a class.
 */
@Serializable
enum class IntegrationLocation {
    /** Integration type appears as a field type */
    FIELD,
    /** Integration type is a constructor parameter */
    CONSTRUCTOR_PARAMETER,
    /** Integration type is a method parameter */
    METHOD_PARAMETER,
    /** Integration type is a method return type */
    METHOD_RETURN_TYPE,
    /** Integration type is called in method body (from bytecode) */
    METHOD_INVOCATION
}

/**
 * Details about a specific integration usage in a class.
 */
@Serializable
data class IntegrationUsage(
    /** Where the integration was found */
    val location: IntegrationLocation,
    /** Field name, method name, or parameter name */
    val name: String,
    /** The integration type FQN (e.g., "ratpack.http.client.HttpClient") */
    val typeFqn: String,
    /** Additional context (e.g., method signature) */
    val context: String? = null
)

/**
 * Summary of an integration detected in the project.
 */
@Serializable
data class IntegrationSummary(
    /** Integration type category */
    val type: IntegrationType,
    /** More specific sub-type */
    val subType: IntegrationSubType,
    /** The primary class/interface FQN that identifies this integration */
    val primaryTypeFqn: String,
    /** Number of classes using this integration */
    val usageCount: Int,
    /** Sample classes using this integration (up to 5) */
    val sampleClasses: List<String>
)

/**
 * Full details about integrations in a specific class.
 */
@Serializable
data class ClassIntegrations(
    /** Fully qualified class name */
    val classFqn: String,
    /** Simple class name */
    val simpleName: String,
    /** All integrations found in this class */
    val integrations: List<IntegrationUsage>,
    /** Summary by integration type */
    val typeSummary: Map<IntegrationType, Int>
)

/**
 * Response for listing all integrations in the project.
 */
@Serializable
data class IntegrationsListResponse(
    /** Summary of integrations by type */
    val integrations: List<IntegrationSummary>,
    /** Total unique classes with integrations */
    val totalClassesWithIntegrations: Int,
    /** Breakdown by integration type */
    val countByType: Map<IntegrationType, Int>
)

/**
 * Response for getting integrations in a specific class.
 */
@Serializable
data class ClassIntegrationsResponse(
    /** The class being queried */
    val classFqn: String,
    /** Integrations in this class */
    val integrations: ClassIntegrations
)

/**
 * Response for finding all usages of an integration type.
 */
@Serializable
data class IntegrationUsagesResponse(
    /** The integration type being queried */
    val integrationType: IntegrationType,
    /** Optional sub-type filter */
    val subType: IntegrationSubType?,
    /** Classes using this integration */
    val classes: List<ClassIntegrations>,
    /** Total count */
    val totalCount: Int
)
```

### 6.5 IntegrationDetector Implementation

```kotlin
// server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/IntegrationDetector.kt

package codelens.classgraph.ratpack

import codelens.core.model.ClassInfo
import codelens.core.model.ClassSource
import codelens.core.model.ratpack.*

/**
 * Detects external service integrations in project classes.
 */
class IntegrationDetector(private val classes: Map<String, ClassInfo>) {

    // Pattern registry mapping FQNs to integration types
    private val patternRegistry: Map<String, Pair<IntegrationType, IntegrationSubType>> = buildPatternRegistry()

    /**
     * Scans all project classes for integrations.
     */
    fun detectAll(): IntegrationsListResponse {
        val classIntegrations = mutableMapOf<String, ClassIntegrations>()
        val integrationUsages = mutableMapOf<String, MutableList<String>>() // typeFqn -> classFqns

        for ((fqn, classInfo) in classes) {
            if (classInfo.source != ClassSource.PROJECT) continue

            val usages = detectInClass(classInfo)
            if (usages.isNotEmpty()) {
                classIntegrations[fqn] = ClassIntegrations(
                    classFqn = fqn,
                    simpleName = classInfo.name.simpleName,
                    integrations = usages,
                    typeSummary = usages.groupBy { getIntegrationType(it.typeFqn) }
                        .mapValues { it.value.size }
                )

                // Track usages by integration type
                usages.forEach { usage ->
                    integrationUsages.getOrPut(usage.typeFqn) { mutableListOf() }.add(fqn)
                }
            }
        }

        // Build summary
        val summaries = integrationUsages.map { (typeFqn, classFqns) ->
            val (type, subType) = patternRegistry[typeFqn]
                ?: (IntegrationType.OTHER to IntegrationSubType.UNKNOWN)
            IntegrationSummary(
                type = type,
                subType = subType,
                primaryTypeFqn = typeFqn,
                usageCount = classFqns.size,
                sampleClasses = classFqns.take(5)
            )
        }.sortedWith(compareBy({ it.type }, { -it.usageCount }))

        val countByType = summaries.groupBy { it.type }
            .mapValues { it.value.sumOf { s -> s.usageCount } }

        return IntegrationsListResponse(
            integrations = summaries,
            totalClassesWithIntegrations = classIntegrations.size,
            countByType = countByType
        )
    }

    /**
     * Detects integrations in a single class.
     */
    fun detectInClass(classInfo: ClassInfo): List<IntegrationUsage> {
        val usages = mutableListOf<IntegrationUsage>()

        // Check fields
        for (field in classInfo.fields) {
            val baseType = extractBaseType(field.type)
            if (isIntegrationType(baseType)) {
                usages.add(IntegrationUsage(
                    location = IntegrationLocation.FIELD,
                    name = field.name,
                    typeFqn = baseType
                ))
            }
        }

        // Check methods
        for (method in classInfo.methods) {
            if (method.isSynthetic) continue

            // Constructor parameters
            if (method.name == "<init>") {
                for (param in method.parameters) {
                    val baseType = extractBaseType(param.type)
                    if (isIntegrationType(baseType)) {
                        usages.add(IntegrationUsage(
                            location = IntegrationLocation.CONSTRUCTOR_PARAMETER,
                            name = param.name,
                            typeFqn = baseType,
                            context = "constructor"
                        ))
                    }
                }
            } else {
                // Method parameters
                for (param in method.parameters) {
                    val baseType = extractBaseType(param.type)
                    if (isIntegrationType(baseType)) {
                        usages.add(IntegrationUsage(
                            location = IntegrationLocation.METHOD_PARAMETER,
                            name = param.name,
                            typeFqn = baseType,
                            context = "${method.name}()"
                        ))
                    }
                }

                // Return type
                val returnType = extractBaseType(method.returnType)
                if (isIntegrationType(returnType)) {
                    usages.add(IntegrationUsage(
                        location = IntegrationLocation.METHOD_RETURN_TYPE,
                        name = method.name,
                        typeFqn = returnType
                    ))
                }
            }
        }

        return usages.distinctBy { "${it.location}:${it.name}:${it.typeFqn}" }
    }

    /**
     * Gets integrations for a specific class by FQN.
     */
    fun getClassIntegrations(fqn: String): ClassIntegrations? {
        val classInfo = classes[fqn] ?: return null
        val usages = detectInClass(classInfo)

        return ClassIntegrations(
            classFqn = fqn,
            simpleName = classInfo.name.simpleName,
            integrations = usages,
            typeSummary = usages.groupBy { getIntegrationType(it.typeFqn) }
                .mapValues { it.value.size }
        )
    }

    /**
     * Finds all classes using a specific integration type.
     */
    fun findByType(
        type: IntegrationType,
        subType: IntegrationSubType? = null
    ): List<ClassIntegrations> {
        return classes.values
            .filter { it.source == ClassSource.PROJECT }
            .mapNotNull { classInfo ->
                val usages = detectInClass(classInfo).filter { usage ->
                    val (usageType, usageSubType) = patternRegistry[usage.typeFqn]
                        ?: (IntegrationType.OTHER to IntegrationSubType.UNKNOWN)
                    usageType == type && (subType == null || usageSubType == subType)
                }

                if (usages.isNotEmpty()) {
                    ClassIntegrations(
                        classFqn = classInfo.name.fqn,
                        simpleName = classInfo.name.simpleName,
                        integrations = usages,
                        typeSummary = mapOf(type to usages.size)
                    )
                } else null
            }
            .sortedBy { it.classFqn }
    }

    private fun isIntegrationType(typeFqn: String): Boolean {
        return patternRegistry.containsKey(typeFqn)
    }

    private fun getIntegrationType(typeFqn: String): IntegrationType {
        return patternRegistry[typeFqn]?.first ?: IntegrationType.OTHER
    }

    private fun extractBaseType(type: String): String {
        return type
            .replace("[]", "")
            .substringBefore("<")
            .trim()
    }

    private fun buildPatternRegistry(): Map<String, Pair<IntegrationType, IntegrationSubType>> {
        return mapOf(
            // HTTP Clients
            "ratpack.http.client.HttpClient" to (IntegrationType.HTTP_CLIENT to IntegrationSubType.RATPACK_HTTP_CLIENT),
            "ratpack.http.client.ReceivedResponse" to (IntegrationType.HTTP_CLIENT to IntegrationSubType.RATPACK_HTTP_CLIENT),
            "org.apache.http.client.HttpClient" to (IntegrationType.HTTP_CLIENT to IntegrationSubType.APACHE_HTTP_CLIENT),
            "org.apache.http.impl.client.CloseableHttpClient" to (IntegrationType.HTTP_CLIENT to IntegrationSubType.APACHE_HTTP_CLIENT),
            "okhttp3.OkHttpClient" to (IntegrationType.HTTP_CLIENT to IntegrationSubType.OKHTTP),
            "java.net.http.HttpClient" to (IntegrationType.HTTP_CLIENT to IntegrationSubType.JAVA_HTTP_CLIENT),

            // DynamoDB
            "com.amazonaws.services.dynamodbv2.AmazonDynamoDB" to (IntegrationType.DATABASE to IntegrationSubType.DYNAMODB),
            "com.amazonaws.services.dynamodbv2.document.DynamoDB" to (IntegrationType.DATABASE to IntegrationSubType.DYNAMODB),
            "com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper" to (IntegrationType.DATABASE to IntegrationSubType.DYNAMODB),
            "software.amazon.awssdk.services.dynamodb.DynamoDbClient" to (IntegrationType.DATABASE to IntegrationSubType.DYNAMODB),
            "software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient" to (IntegrationType.DATABASE to IntegrationSubType.DYNAMODB),

            // SQS
            "com.amazonaws.services.sqs.AmazonSQS" to (IntegrationType.MESSAGE_QUEUE to IntegrationSubType.SQS),
            "software.amazon.awssdk.services.sqs.SqsClient" to (IntegrationType.MESSAGE_QUEUE to IntegrationSubType.SQS),

            // SNS
            "com.amazonaws.services.sns.AmazonSNS" to (IntegrationType.MESSAGE_QUEUE to IntegrationSubType.SNS),
            "software.amazon.awssdk.services.sns.SnsClient" to (IntegrationType.MESSAGE_QUEUE to IntegrationSubType.SNS),

            // Redis
            "io.lettuce.core.RedisClient" to (IntegrationType.CACHE to IntegrationSubType.REDIS_LETTUCE),
            "io.lettuce.core.api.sync.RedisCommands" to (IntegrationType.CACHE to IntegrationSubType.REDIS_LETTUCE),
            "redis.clients.jedis.Jedis" to (IntegrationType.CACHE to IntegrationSubType.REDIS_JEDIS),

            // gRPC
            "io.grpc.ManagedChannel" to (IntegrationType.GRPC to IntegrationSubType.GRPC_CLIENT),

            // S3
            "com.amazonaws.services.s3.AmazonS3" to (IntegrationType.FILE_STORAGE to IntegrationSubType.S3),
            "software.amazon.awssdk.services.s3.S3Client" to (IntegrationType.FILE_STORAGE to IntegrationSubType.S3),

            // JDBC
            "java.sql.Connection" to (IntegrationType.DATABASE to IntegrationSubType.JDBC),
            "javax.sql.DataSource" to (IntegrationType.DATABASE to IntegrationSubType.JDBC)
        )
    }
}
```

### 6.6 API Endpoints

#### GET /api/v1/ratpack/integrations

List all detected integrations in the project.

**Request:**
```
GET /api/v1/ratpack/integrations
GET /api/v1/ratpack/integrations?type=HTTP_CLIENT
```

**Query Parameters:**
- `type` (optional): Filter by integration type (HTTP_CLIENT, DATABASE, MESSAGE_QUEUE, etc.)

**Response (200 OK):**
```json
{
  "integrations": [
    {
      "type": "HTTP_CLIENT",
      "subType": "RATPACK_HTTP_CLIENT",
      "primaryTypeFqn": "ratpack.http.client.HttpClient",
      "usageCount": 11,
      "sampleClasses": [
        "com.example.clients.UserServiceClient",
        "com.example.clients.PaymentClient",
        "com.example.handlers.ProxyHandler"
      ]
    },
    {
      "type": "DATABASE",
      "subType": "DYNAMODB",
      "primaryTypeFqn": "com.amazonaws.services.dynamodbv2.document.DynamoDB",
      "usageCount": 5,
      "sampleClasses": [
        "com.example.repositories.UserRepository",
        "com.example.repositories.OrderRepository"
      ]
    }
  ],
  "totalClassesWithIntegrations": 23,
  "countByType": {
    "HTTP_CLIENT": 11,
    "DATABASE": 5,
    "MESSAGE_QUEUE": 4,
    "CACHE": 3
  }
}
```

#### GET /api/v1/ratpack/integrations/{fqn...}

Get integrations for a specific class.

**Request:**
```
GET /api/v1/ratpack/integrations/com.example.handlers.UserHandler
```

**Response (200 OK):**
```json
{
  "classFqn": "com.example.handlers.UserHandler",
  "integrations": {
    "classFqn": "com.example.handlers.UserHandler",
    "simpleName": "UserHandler",
    "integrations": [
      {
        "location": "FIELD",
        "name": "httpClient",
        "typeFqn": "ratpack.http.client.HttpClient"
      },
      {
        "location": "FIELD",
        "name": "dynamoDB",
        "typeFqn": "com.amazonaws.services.dynamodbv2.document.DynamoDB"
      }
    ],
    "typeSummary": {
      "HTTP_CLIENT": 1,
      "DATABASE": 1
    }
  }
}
```

#### GET /api/v1/ratpack/integrations/by-type/{type}

Get all classes using a specific integration type.

**Request:**
```
GET /api/v1/ratpack/integrations/by-type/HTTP_CLIENT
GET /api/v1/ratpack/integrations/by-type/DATABASE?subType=DYNAMODB
```

**Response (200 OK):**
```json
{
  "integrationType": "HTTP_CLIENT",
  "subType": null,
  "classes": [
    {
      "classFqn": "com.example.clients.UserServiceClient",
      "simpleName": "UserServiceClient",
      "integrations": [
        {
          "location": "FIELD",
          "name": "httpClient",
          "typeFqn": "ratpack.http.client.HttpClient"
        }
      ],
      "typeSummary": { "HTTP_CLIENT": 1 }
    }
  ],
  "totalCount": 11
}
```

### 6.7 CLI Commands

```python
# cli/src/codelens_cli/commands/integrations.py

"""External service integration detection commands."""

from typing import Optional

import typer
from rich.console import Console
from rich.table import Table
from rich.tree import Tree

from codelens_cli.commands.common import ensure_server_running, get_client, handle_api_errors
from codelens_cli.models import (
    IntegrationsListResponse,
    ClassIntegrationsResponse,
    IntegrationUsagesResponse,
)
from codelens_cli.output import is_tty, print_json

app = typer.Typer(
    name="integrations",
    help="Detect external service integrations (HTTP clients, databases, queues).",
    no_args_is_help=True,
)

console = Console()


@app.command(name="list")
def list_integrations(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    integration_type: Optional[str] = typer.Option(
        None, "--type", "-t",
        help="Filter by type: HTTP_CLIENT, DATABASE, MESSAGE_QUEUE, CACHE, GRPC, FILE_STORAGE"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """List all external service integrations in the project."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.list_integrations(integration_type=integration_type)

        if json_output or not is_tty():
            print_json(result)
        else:
            response = IntegrationsListResponse.model_validate(result)

            # Summary header
            console.print(f"\n[bold]External Service Integrations[/bold]")
            console.print(f"Classes with integrations: {response.total_classes_with_integrations}\n")

            # Summary by type
            if response.count_by_type:
                table = Table(title="By Type")
                table.add_column("Type", style="cyan")
                table.add_column("Count", justify="right")

                for itype, count in sorted(response.count_by_type.items()):
                    table.add_row(itype, str(count))
                console.print(table)
                console.print()

            # Detailed list
            table = Table(title="Integration Details")
            table.add_column("Type", style="cyan")
            table.add_column("Sub-Type", style="green")
            table.add_column("Primary Class")
            table.add_column("Usages", justify="right")
            table.add_column("Sample Classes")

            for integration in response.integrations:
                samples = ", ".join(
                    c.split(".")[-1] for c in integration.sample_classes[:3]
                )
                if len(integration.sample_classes) > 3:
                    samples += f" (+{len(integration.sample_classes) - 3} more)"

                table.add_row(
                    integration.type,
                    integration.sub_type,
                    integration.primary_type_fqn.split(".")[-1],
                    str(integration.usage_count),
                    samples
                )

            console.print(table)


@app.command(name="show")
def show_class_integrations(
    fqn: str = typer.Argument(help="Fully qualified class name"),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show integrations for a specific class."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_class_integrations(fqn)

        if json_output or not is_tty():
            print_json(result)
        else:
            response = ClassIntegrationsResponse.model_validate(result)
            integrations = response.integrations

            console.print(f"\n[bold]{integrations.simple_name}[/bold]")
            console.print(f"[dim]{integrations.class_fqn}[/dim]\n")

            if not integrations.integrations:
                console.print("[yellow]No integrations detected[/yellow]")
                return

            # Group by type
            by_type = {}
            for usage in integrations.integrations:
                itype = usage.type_fqn.split(".")[-1]  # Simplify for display
                by_type.setdefault(itype, []).append(usage)

            tree = Tree("[bold]Integrations[/bold]")
            for type_name, usages in by_type.items():
                type_branch = tree.add(f"[cyan]{type_name}[/cyan]")
                for usage in usages:
                    location_str = f"[green]{usage.location}[/green]"
                    name_str = f"[white]{usage.name}[/white]"
                    context = f" [dim]({usage.context})[/dim]" if usage.context else ""
                    type_branch.add(f"{location_str}: {name_str}{context}")

            console.print(tree)


@app.command(name="find")
def find_by_type(
    integration_type: str = typer.Argument(
        help="Integration type: HTTP_CLIENT, DATABASE, MESSAGE_QUEUE, CACHE, GRPC, FILE_STORAGE"
    ),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    sub_type: Optional[str] = typer.Option(
        None, "--sub-type", "-s",
        help="Sub-type: DYNAMODB, SQS, REDIS_LETTUCE, etc."
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Find all classes using a specific integration type."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.find_integrations_by_type(integration_type, sub_type)

        if json_output or not is_tty():
            print_json(result)
        else:
            response = IntegrationUsagesResponse.model_validate(result)

            title = f"Classes using {response.integration_type}"
            if response.sub_type:
                title += f" ({response.sub_type})"

            console.print(f"\n[bold]{title}[/bold]")
            console.print(f"Total: {response.total_count} classes\n")

            table = Table()
            table.add_column("Class", style="cyan")
            table.add_column("Location")
            table.add_column("Name")
            table.add_column("Type")

            for cls in response.classes:
                for usage in cls.integrations:
                    table.add_row(
                        cls.simple_name,
                        usage.location,
                        usage.name,
                        usage.type_fqn.split(".")[-1]
                    )

            console.print(table)
```

---

## Feature 7: Registry Access Analysis

### 7.1 Registry Access Detection Strategy

Registry access is Ratpack's dependency injection mechanism. We need to detect:
1. `ctx.get(Type.class)` - Get required dependency
2. `ctx.maybeGet(Type.class)` - Get optional dependency
3. `ctx.getAll(Type.class)` - Get all instances
4. `registry.get(Type.class)` - Direct registry access
5. `chain.getRegistry().get(Type.class)` - Chain-based access

### 7.2 ClassGraph Method Call Analysis

ClassGraph can scan method calls in bytecode using the `enableMethodInfo()` and `enableInterClassDependencies()` options:

```kotlin
// Enhanced scan configuration
ClassGraph()
    .overrideClasspath(classpathStr)
    .enableAllInfo()
    .enableInterClassDependencies()  // Enables method call tracking
    .scan()

// Access method calls from bytecode
for (cgClass in scanResult.allClasses) {
    // Get classes that this class references in method bodies
    val classDependencies = cgClass.classDependencies

    // For more detailed method call info, we need to examine
    // the constant pool or use ASM for deeper inspection
}
```

**Important Limitation**: ClassGraph's method call detection is limited. For precise `ctx.get()` detection, we may need to:
1. Use ASM to read bytecode directly
2. Or rely on type analysis (fields/parameters of Context type)

### 7.3 Type Parameter Extraction Challenges

The main challenge is extracting the type parameter from `ctx.get(UserService.class)`:

```java
// In bytecode, this becomes:
// INVOKEVIRTUAL ratpack/handling/Context.get(Ljava/lang/Class;)Ljava/lang/Object;
// LDC UserService.class

// The type parameter is passed as a Class argument, not embedded in the generic signature
// We need to analyze the preceding LDC instruction to find the type
```

**Solution Approaches**:

1. **Conservative Approach** (Recommended initially):
   - Detect that `Context.get()` is called
   - Report the containing class/method
   - Let the LLM examine the source for the actual type

2. **Advanced Approach** (Future enhancement):
   - Use ASM to parse bytecode
   - Track LDC instructions before method calls
   - Extract the loaded class constant

### 7.4 Data Models

```kotlin
// server/core/src/main/kotlin/codelens/core/model/ratpack/RegistryModels.kt

package codelens.core.model.ratpack

import kotlinx.serialization.Serializable

/**
 * Type of registry access method.
 */
@Serializable
enum class RegistryAccessMethod {
    /** Context.get(Class) - required dependency */
    GET,
    /** Context.maybeGet(Class) - optional dependency */
    MAYBE_GET,
    /** Context.getAll(Class) - all instances */
    GET_ALL,
    /** Registry.get(Class) - direct registry access */
    REGISTRY_GET,
    /** Registry.maybeGet(Class) - optional from registry */
    REGISTRY_MAYBE_GET
}

/**
 * A single registry access detected in code.
 */
@Serializable
data class RegistryAccess(
    /** Method where the access occurs */
    val methodName: String,
    /** Type of registry access */
    val accessMethod: RegistryAccessMethod,
    /** The type being retrieved (if determinable) */
    val retrievedType: String?,
    /** Line number (if available from bytecode) */
    val lineNumber: Int?,
    /** Is this in a handler's handle() method? */
    val inHandleMethod: Boolean
)

/**
 * Registry accesses for a single class.
 */
@Serializable
data class ClassRegistryAccesses(
    /** Fully qualified class name */
    val classFqn: String,
    /** Simple class name */
    val simpleName: String,
    /** Is this class a Handler implementation? */
    val isHandler: Boolean,
    /** All registry accesses in this class */
    val accesses: List<RegistryAccess>,
    /** Count by access method type */
    val countByMethod: Map<RegistryAccessMethod, Int>,
    /** Migration hints for this class */
    val migrationHints: List<MigrationHint>
)

/**
 * Migration hint for converting registry access to DI.
 */
@Serializable
data class MigrationHint(
    /** Hint type/category */
    val type: MigrationHintType,
    /** Human-readable message */
    val message: String,
    /** Suggested action */
    val suggestion: String,
    /** Related method name */
    val relatedMethod: String?
)

@Serializable
enum class MigrationHintType {
    /** Convert ctx.get() to constructor injection */
    CONVERT_TO_CONSTRUCTOR_INJECTION,
    /** ctx.maybeGet() needs Optional handling */
    HANDLE_OPTIONAL_DEPENDENCY,
    /** ctx.getAll() needs collection injection */
    HANDLE_COLLECTION_INJECTION,
    /** Request-scoped access, may need Provider */
    REQUEST_SCOPED_ACCESS,
    /** Complex pattern, needs manual review */
    NEEDS_MANUAL_REVIEW
}

/**
 * Summary of all registry accesses in the project.
 */
@Serializable
data class RegistrySummaryResponse(
    /** Total classes with registry access */
    val totalClasses: Int,
    /** Total number of get() calls */
    val totalGetCalls: Int,
    /** Total number of maybeGet() calls */
    val totalMaybeGetCalls: Int,
    /** Total number of getAll() calls */
    val totalGetAllCalls: Int,
    /** Classes with the most registry accesses */
    val topClasses: List<ClassRegistrySummary>,
    /** Most commonly retrieved types */
    val topRetrievedTypes: List<RetrievedTypeSummary>
)

@Serializable
data class ClassRegistrySummary(
    val classFqn: String,
    val simpleName: String,
    val totalAccesses: Int,
    val isHandler: Boolean
)

@Serializable
data class RetrievedTypeSummary(
    val typeFqn: String,
    val usageCount: Int,
    val accessMethods: List<RegistryAccessMethod>
)

/**
 * Response for class registry details.
 */
@Serializable
data class ClassRegistryResponse(
    val classFqn: String,
    val accesses: ClassRegistryAccesses
)

/**
 * Response for registry usages list.
 */
@Serializable
data class RegistryUsagesResponse(
    val classes: List<ClassRegistryAccesses>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int
)
```

### 7.5 RegistryAccessDetector Implementation

```kotlin
// server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/RegistryAccessDetector.kt

package codelens.classgraph.ratpack

import codelens.core.model.ClassInfo
import codelens.core.model.ClassSource
import codelens.core.model.ratpack.*

/**
 * Detects Ratpack Registry access patterns in project classes.
 *
 * Primary detection is based on:
 * 1. Fields/parameters of type ratpack.handling.Context
 * 2. Fields/parameters of type ratpack.registry.Registry
 * 3. Classes implementing ratpack.handling.Handler
 */
class RegistryAccessDetector(private val classes: Map<String, ClassInfo>) {

    companion object {
        private const val CONTEXT_TYPE = "ratpack.handling.Context"
        private const val REGISTRY_TYPE = "ratpack.registry.Registry"
        private const val HANDLER_INTERFACE = "ratpack.handling.Handler"

        private val REGISTRY_METHODS = setOf("get", "maybeGet", "getAll")
    }

    /**
     * Generates a summary of all registry accesses in the project.
     */
    fun getSummary(): RegistrySummaryResponse {
        val allAccesses = detectAll()

        var totalGet = 0
        var totalMaybeGet = 0
        var totalGetAll = 0
        val typeUsages = mutableMapOf<String, MutableList<RegistryAccessMethod>>()

        for (classAccesses in allAccesses) {
            for (access in classAccesses.accesses) {
                when (access.accessMethod) {
                    RegistryAccessMethod.GET, RegistryAccessMethod.REGISTRY_GET -> totalGet++
                    RegistryAccessMethod.MAYBE_GET, RegistryAccessMethod.REGISTRY_MAYBE_GET -> totalMaybeGet++
                    RegistryAccessMethod.GET_ALL -> totalGetAll++
                }

                access.retrievedType?.let { type ->
                    typeUsages.getOrPut(type) { mutableListOf() }.add(access.accessMethod)
                }
            }
        }

        val topClasses = allAccesses
            .sortedByDescending { it.accesses.size }
            .take(10)
            .map { ClassRegistrySummary(
                classFqn = it.classFqn,
                simpleName = it.simpleName,
                totalAccesses = it.accesses.size,
                isHandler = it.isHandler
            )}

        val topTypes = typeUsages
            .map { (type, methods) -> RetrievedTypeSummary(
                typeFqn = type,
                usageCount = methods.size,
                accessMethods = methods.distinct()
            )}
            .sortedByDescending { it.usageCount }
            .take(10)

        return RegistrySummaryResponse(
            totalClasses = allAccesses.size,
            totalGetCalls = totalGet,
            totalMaybeGetCalls = totalMaybeGet,
            totalGetAllCalls = totalGetAll,
            topClasses = topClasses,
            topRetrievedTypes = topTypes
        )
    }

    /**
     * Detects registry access in all project classes.
     */
    fun detectAll(): List<ClassRegistryAccesses> {
        return classes.values
            .filter { it.source == ClassSource.PROJECT }
            .mapNotNull { detectInClass(it) }
            .filter { it.accesses.isNotEmpty() || it.isHandler }
            .sortedBy { it.classFqn }
    }

    /**
     * Detects registry access in a single class.
     */
    fun detectInClass(classInfo: ClassInfo): ClassRegistryAccesses? {
        val isHandler = classInfo.interfaces.contains(HANDLER_INTERFACE) ||
            hasHandlerSuperclass(classInfo)

        val accesses = mutableListOf<RegistryAccess>()

        // Check for Context/Registry parameters in methods
        for (method in classInfo.methods) {
            if (method.isSynthetic) continue

            val hasContextParam = method.parameters.any {
                it.type == CONTEXT_TYPE || it.type.contains(CONTEXT_TYPE)
            }
            val hasRegistryParam = method.parameters.any {
                it.type == REGISTRY_TYPE || it.type.contains(REGISTRY_TYPE)
            }

            if (hasContextParam) {
                // This method receives a Context - likely uses registry access
                val isHandleMethod = method.name == "handle" && isHandler

                // We can't determine the exact get() calls without ASM,
                // but we know this method has Context access
                accesses.add(RegistryAccess(
                    methodName = method.name,
                    accessMethod = RegistryAccessMethod.GET,
                    retrievedType = null, // Would need bytecode analysis
                    lineNumber = method.startLine,
                    inHandleMethod = isHandleMethod
                ))
            }

            if (hasRegistryParam) {
                accesses.add(RegistryAccess(
                    methodName = method.name,
                    accessMethod = RegistryAccessMethod.REGISTRY_GET,
                    retrievedType = null,
                    lineNumber = method.startLine,
                    inHandleMethod = false
                ))
            }
        }

        // Check for Context/Registry fields
        for (field in classInfo.fields) {
            if (field.type == CONTEXT_TYPE || field.type.contains(CONTEXT_TYPE)) {
                accesses.add(RegistryAccess(
                    methodName = "<field:${field.name}>",
                    accessMethod = RegistryAccessMethod.GET,
                    retrievedType = null,
                    lineNumber = null,
                    inHandleMethod = false
                ))
            }
            if (field.type == REGISTRY_TYPE || field.type.contains(REGISTRY_TYPE)) {
                accesses.add(RegistryAccess(
                    methodName = "<field:${field.name}>",
                    accessMethod = RegistryAccessMethod.REGISTRY_GET,
                    retrievedType = null,
                    lineNumber = null,
                    inHandleMethod = false
                ))
            }
        }

        val hints = generateMigrationHints(classInfo, accesses, isHandler)

        val countByMethod = accesses
            .groupBy { it.accessMethod }
            .mapValues { it.value.size }

        return ClassRegistryAccesses(
            classFqn = classInfo.name.fqn,
            simpleName = classInfo.name.simpleName,
            isHandler = isHandler,
            accesses = accesses,
            countByMethod = countByMethod,
            migrationHints = hints
        )
    }

    /**
     * Gets registry accesses for a specific class.
     */
    fun getClassAccesses(fqn: String): ClassRegistryAccesses? {
        val classInfo = classes[fqn] ?: return null
        return detectInClass(classInfo)
    }

    private fun hasHandlerSuperclass(classInfo: ClassInfo): Boolean {
        var current: ClassInfo? = classInfo
        val visited = mutableSetOf<String>()

        while (current != null && current.name.fqn !in visited) {
            visited.add(current.name.fqn)
            if (current.interfaces.contains(HANDLER_INTERFACE)) return true
            current = current.superclass?.let { classes[it] }
        }
        return false
    }

    private fun generateMigrationHints(
        classInfo: ClassInfo,
        accesses: List<RegistryAccess>,
        isHandler: Boolean
    ): List<MigrationHint> {
        val hints = mutableListOf<MigrationHint>()

        // Handler-specific hints
        if (isHandler) {
            val handleMethodAccesses = accesses.filter { it.inHandleMethod }
            if (handleMethodAccesses.isNotEmpty()) {
                hints.add(MigrationHint(
                    type = MigrationHintType.CONVERT_TO_CONSTRUCTOR_INJECTION,
                    message = "Handler uses ctx.get() in handle() method",
                    suggestion = "Convert to constructor injection. Services retrieved in handle() " +
                        "can typically be injected via constructor if they are request-independent.",
                    relatedMethod = "handle"
                ))
            }
        }

        // Check for maybeGet patterns
        val maybeGetAccesses = accesses.filter {
            it.accessMethod == RegistryAccessMethod.MAYBE_GET ||
            it.accessMethod == RegistryAccessMethod.REGISTRY_MAYBE_GET
        }
        if (maybeGetAccesses.isNotEmpty()) {
            hints.add(MigrationHint(
                type = MigrationHintType.HANDLE_OPTIONAL_DEPENDENCY,
                message = "Class uses maybeGet() for optional dependencies",
                suggestion = "Use @Inject Optional<T> or check if the dependency " +
                    "should be required instead.",
                relatedMethod = maybeGetAccesses.firstOrNull()?.methodName
            ))
        }

        // Check for getAll patterns
        val getAllAccesses = accesses.filter { it.accessMethod == RegistryAccessMethod.GET_ALL }
        if (getAllAccesses.isNotEmpty()) {
            hints.add(MigrationHint(
                type = MigrationHintType.HANDLE_COLLECTION_INJECTION,
                message = "Class uses getAll() for collection dependencies",
                suggestion = "Use @Inject List<T> or Set<T> for multi-binding patterns.",
                relatedMethod = getAllAccesses.firstOrNull()?.methodName
            ))
        }

        // Multiple Context fields suggests complex pattern
        val contextFields = classInfo.fields.count {
            it.type == CONTEXT_TYPE || it.type.contains(CONTEXT_TYPE)
        }
        if (contextFields > 0) {
            hints.add(MigrationHint(
                type = MigrationHintType.REQUEST_SCOPED_ACCESS,
                message = "Class stores Context reference in field",
                suggestion = "This pattern stores request-scoped Context. Consider using " +
                    "Provider<Context> or restructuring to pass Context through method parameters.",
                relatedMethod = null
            ))
        }

        return hints
    }
}
```

### 7.6 API Endpoints

#### GET /api/v1/ratpack/registry

Get summary of all registry accesses.

**Response (200 OK):**
```json
{
  "totalClasses": 45,
  "totalGetCalls": 127,
  "totalMaybeGetCalls": 12,
  "totalGetAllCalls": 3,
  "topClasses": [
    {
      "classFqn": "com.example.handlers.ComplexHandler",
      "simpleName": "ComplexHandler",
      "totalAccesses": 8,
      "isHandler": true
    }
  ],
  "topRetrievedTypes": [
    {
      "typeFqn": "com.example.services.UserService",
      "usageCount": 15,
      "accessMethods": ["GET"]
    }
  ]
}
```

#### GET /api/v1/ratpack/registry/{fqn...}

Get registry accesses for a specific class.

**Response (200 OK):**
```json
{
  "classFqn": "com.example.handlers.UserHandler",
  "accesses": {
    "classFqn": "com.example.handlers.UserHandler",
    "simpleName": "UserHandler",
    "isHandler": true,
    "accesses": [
      {
        "methodName": "handle",
        "accessMethod": "GET",
        "retrievedType": null,
        "lineNumber": 25,
        "inHandleMethod": true
      }
    ],
    "countByMethod": {
      "GET": 1
    },
    "migrationHints": [
      {
        "type": "CONVERT_TO_CONSTRUCTOR_INJECTION",
        "message": "Handler uses ctx.get() in handle() method",
        "suggestion": "Convert to constructor injection...",
        "relatedMethod": "handle"
      }
    ]
  }
}
```

#### GET /api/v1/ratpack/registry/usages

List all classes with registry access.

**Query Parameters:**
- `handlersOnly` (optional, default: false): Only show Handler implementations
- `page` (optional, default: 0): Page number
- `size` (optional, default: 50): Page size

**Response (200 OK):**
```json
{
  "classes": [...],
  "totalCount": 45,
  "page": 0,
  "pageSize": 50,
  "totalPages": 1
}
```

### 7.7 CLI Commands

```python
# cli/src/codelens_cli/commands/registry.py

"""Registry access analysis commands."""

from typing import Optional

import typer
from rich.console import Console
from rich.table import Table
from rich.panel import Panel
from rich.tree import Tree

from codelens_cli.commands.common import ensure_server_running, get_client, handle_api_errors
from codelens_cli.models import (
    RegistrySummaryResponse,
    ClassRegistryResponse,
    RegistryUsagesResponse,
)
from codelens_cli.output import is_tty, print_json

app = typer.Typer(
    name="registry",
    help="Analyze Ratpack Registry access patterns (ctx.get(), etc.).",
    no_args_is_help=True,
)

console = Console()


@app.command(name="summary")
def registry_summary(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show summary of Registry access patterns."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_registry_summary()

        if json_output or not is_tty():
            print_json(result)
        else:
            response = RegistrySummaryResponse.model_validate(result)

            console.print("\n[bold]Registry Access Summary[/bold]\n")

            # Overview stats
            stats_table = Table(show_header=False, box=None)
            stats_table.add_column("Metric", style="cyan")
            stats_table.add_column("Value", justify="right")

            stats_table.add_row("Classes with registry access", str(response.total_classes))
            stats_table.add_row("Total get() calls", str(response.total_get_calls))
            stats_table.add_row("Total maybeGet() calls", str(response.total_maybe_get_calls))
            stats_table.add_row("Total getAll() calls", str(response.total_get_all_calls))

            console.print(Panel(stats_table, title="Overview"))
            console.print()

            # Top classes
            if response.top_classes:
                table = Table(title="Top Classes by Registry Access")
                table.add_column("Class", style="cyan")
                table.add_column("Accesses", justify="right")
                table.add_column("Handler?")

                for cls in response.top_classes[:10]:
                    handler_mark = "[green]Yes[/green]" if cls.is_handler else "[dim]No[/dim]"
                    table.add_row(cls.simple_name, str(cls.total_accesses), handler_mark)

                console.print(table)
                console.print()

            # Top retrieved types
            if response.top_retrieved_types:
                table = Table(title="Most Retrieved Types")
                table.add_column("Type", style="cyan")
                table.add_column("Usages", justify="right")
                table.add_column("Methods")

                for t in response.top_retrieved_types[:10]:
                    methods = ", ".join(t.access_methods)
                    table.add_row(
                        t.type_fqn.split(".")[-1],
                        str(t.usage_count),
                        methods
                    )

                console.print(table)


@app.command(name="show")
def show_class_registry(
    fqn: str = typer.Argument(help="Fully qualified class name"),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show registry accesses for a specific class."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_class_registry(fqn)

        if json_output or not is_tty():
            print_json(result)
        else:
            response = ClassRegistryResponse.model_validate(result)
            accesses = response.accesses

            handler_badge = " [green][Handler][/green]" if accesses.is_handler else ""
            console.print(f"\n[bold]{accesses.simple_name}[/bold]{handler_badge}")
            console.print(f"[dim]{accesses.class_fqn}[/dim]\n")

            # Accesses
            if accesses.accesses:
                table = Table(title="Registry Accesses")
                table.add_column("Method")
                table.add_column("Access Type", style="cyan")
                table.add_column("Retrieved Type")
                table.add_column("In handle()?")

                for access in accesses.accesses:
                    handle_mark = "[green]Yes[/green]" if access.in_handle_method else ""
                    retrieved = access.retrieved_type or "[dim]unknown[/dim]"
                    table.add_row(
                        access.method_name,
                        access.access_method,
                        retrieved,
                        handle_mark
                    )

                console.print(table)
                console.print()

            # Migration hints
            if accesses.migration_hints:
                console.print("[bold]Migration Hints:[/bold]")
                for hint in accesses.migration_hints:
                    console.print(f"\n  [yellow]{hint.type}[/yellow]")
                    console.print(f"  {hint.message}")
                    console.print(f"  [green]Suggestion:[/green] {hint.suggestion}")
                    if hint.related_method:
                        console.print(f"  [dim]Related: {hint.related_method}[/dim]")


@app.command(name="usages")
def list_usages(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    handlers_only: bool = typer.Option(
        False, "--handlers-only", "-H", help="Only show Handler implementations"
    ),
    page: int = typer.Option(0, "--page", help="Page number"),
    size: int = typer.Option(50, "--size", help="Page size"),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """List all classes with registry access."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_registry_usages(
            handlers_only=handlers_only, page=page, size=size
        )

        if json_output or not is_tty():
            print_json(result)
        else:
            response = RegistryUsagesResponse.model_validate(result)

            console.print(f"\n[bold]Registry Usages[/bold]")
            console.print(f"Total: {response.total_count} classes")
            console.print(f"Page {response.page + 1} of {response.total_pages}\n")

            table = Table()
            table.add_column("Class", style="cyan")
            table.add_column("Handler?")
            table.add_column("get()", justify="right")
            table.add_column("maybeGet()", justify="right")
            table.add_column("getAll()", justify="right")
            table.add_column("Hints")

            for cls in response.classes:
                handler = "[green]Yes[/green]" if cls.is_handler else ""
                get_count = cls.count_by_method.get("GET", 0) + cls.count_by_method.get("REGISTRY_GET", 0)
                maybe_count = cls.count_by_method.get("MAYBE_GET", 0) + cls.count_by_method.get("REGISTRY_MAYBE_GET", 0)
                getall_count = cls.count_by_method.get("GET_ALL", 0)
                hints_count = len(cls.migration_hints)

                table.add_row(
                    cls.simple_name,
                    handler,
                    str(get_count) if get_count else "",
                    str(maybe_count) if maybe_count else "",
                    str(getall_count) if getall_count else "",
                    f"[yellow]{hints_count}[/yellow]" if hints_count else ""
                )

            console.print(table)
```

---

## Implementation Progress Tracking

### Feature 5: Source Code Retrieval

#### Server Implementation
- [ ] Extend Gradle resolver init script to capture source roots (add `codelensSourceRoots`)
- [ ] Extend `ResolvedClasspath` to include `sourceRoots: List<SourceRoot>`
- [ ] Create `server/core/src/main/kotlin/codelens/core/model/source/SourceModels.kt`
- [ ] Create `server/classgraph/src/main/kotlin/codelens/classgraph/source/SourceResolver.kt`
- [ ] Create `server/classgraph/src/main/kotlin/codelens/classgraph/source/MethodExtractor.kt`
- [ ] Add `getSource()` and `getMethodSource()` to `AnalysisService`
- [ ] Create `server/app/src/main/kotlin/codelens/server/routes/SourceRoutes.kt`
- [ ] Register routes in `Application.kt`
- [ ] Write unit tests for `SourceResolver`
- [ ] Write unit tests for `MethodExtractor`
- [ ] Write integration tests for source endpoints

#### CLI Implementation
- [ ] Add source models to `cli/src/codelens_cli/models.py`
- [ ] Add client methods to `cli/src/codelens_cli/client.py`
- [ ] Create `cli/src/codelens_cli/commands/source.py`
- [ ] Register source commands in `main.py`
- [ ] Write CLI tests

---

### Feature 6: External Service Integration Detection

#### Server Implementation
- [ ] Create `server/core/src/main/kotlin/codelens/core/model/ratpack/IntegrationModels.kt`
- [ ] Create `server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/IntegrationDetector.kt`
- [ ] Add integration detection methods to `AnalysisService`
- [ ] Add integration routes to `RatpackRoutes.kt` (or create new routes file)
- [ ] Write unit tests for `IntegrationDetector`
- [ ] Write integration tests

#### CLI Implementation
- [ ] Add integration models to `models.py`
- [ ] Add client methods to `client.py`
- [ ] Create `cli/src/codelens_cli/commands/integrations.py`
- [ ] Register commands in `main.py`
- [ ] Write CLI tests

#### Testing
- [ ] Add integration examples to test fixtures (HTTP client, DynamoDB, SQS)
- [ ] Manual test against real projects

---

### Feature 7: Registry Access Analysis

#### Server Implementation
- [ ] Create `server/core/src/main/kotlin/codelens/core/model/ratpack/RegistryModels.kt`
- [ ] Create `server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/RegistryAccessDetector.kt`
- [ ] Add registry detection methods to `AnalysisService`
- [ ] Add registry routes
- [ ] Write unit tests
- [ ] Write integration tests

#### CLI Implementation
- [ ] Add registry models to `models.py`
- [ ] Add client methods to `client.py`
- [ ] Create `cli/src/codelens_cli/commands/registry.py`
- [ ] Register commands in `main.py`
- [ ] Write CLI tests

#### Testing
- [ ] Add Registry access examples to test fixtures
- [ ] Manual test against real projects

---

## Files to Create/Modify

### New Files (Server)
- `server/core/src/main/kotlin/codelens/core/model/source/SourceModels.kt`
- `server/core/src/main/kotlin/codelens/core/model/ratpack/IntegrationModels.kt`
- `server/core/src/main/kotlin/codelens/core/model/ratpack/RegistryModels.kt`
- `server/classgraph/src/main/kotlin/codelens/classgraph/source/SourceResolver.kt`
- `server/classgraph/src/main/kotlin/codelens/classgraph/source/MethodExtractor.kt`
- `server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/IntegrationDetector.kt`
- `server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/RegistryAccessDetector.kt`
- `server/app/src/main/kotlin/codelens/server/routes/SourceRoutes.kt`

### New Files (CLI)
- `cli/src/codelens_cli/commands/source.py`
- `cli/src/codelens_cli/commands/integrations.py`
- `cli/src/codelens_cli/commands/registry.py`

### Modified Files (Server)
- `server/gradle-resolver/src/main/kotlin/codelens/gradle/ClasspathResolver.kt` - Add sourceRoots
- `server/gradle-resolver/src/main/kotlin/codelens/gradle/GradleProjectResolver.kt` - Capture source roots
- `server/app/src/main/kotlin/codelens/server/Application.kt` - Register new routes
- `server/app/src/main/kotlin/codelens/server/services/AnalysisService.kt` - Add new methods
- `server/app/src/main/kotlin/codelens/server/routes/RatpackRoutes.kt` - Add integration/registry routes (or create separate files)

### Modified Files (CLI)
- `cli/src/codelens_cli/main.py` - Register new command groups
- `cli/src/codelens_cli/models.py` - Add new model classes
- `cli/src/codelens_cli/client.py` - Add new API methods

### Test Files to Create
- `server/classgraph/src/test/kotlin/codelens/classgraph/source/SourceResolverTest.kt`
- `server/classgraph/src/test/kotlin/codelens/classgraph/ratpack/IntegrationDetectorTest.kt`
- `server/classgraph/src/test/kotlin/codelens/classgraph/ratpack/RegistryAccessDetectorTest.kt`
- `cli/tests/test_source.py`
- `cli/tests/test_integrations.py`
- `cli/tests/test_registry.py`

---

## Acceptance Criteria

### Functional
- [ ] `codelens source show <FQN>` returns full source code with syntax highlighting
- [ ] `codelens source method <FQN> <method>` returns method source with line numbers
- [ ] `codelens integrations list` shows all detected external services by type
- [ ] `codelens integrations show <FQN>` shows integrations for a specific class
- [ ] `codelens registry summary` shows overview of registry access patterns
- [ ] `codelens registry show <FQN>` shows registry accesses with migration hints
- [ ] All commands support `--json` flag for programmatic access

### Quality
- [ ] Source retrieval works for both Java and Kotlin files
- [ ] Multi-module project source resolution works correctly
- [ ] Integration detection covers all documented patterns
- [ ] Migration hints are actionable and accurate

### Performance
- [ ] Source retrieval < 100ms per class
- [ ] Integration scan runs during initial scan (no additional cost)
- [ ] Registry analysis runs during initial scan (no additional cost)

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
