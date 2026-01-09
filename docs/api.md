# CodeLens Server API Reference

The CodeLens server exposes a REST API for bytecode analysis. All endpoints return JSON responses.

## Base URL

```
http://127.0.0.1:{port}
```

The server binds to localhost by default. Port is automatically assigned from the range 8080-8180.

## Admin Endpoints

These endpoints manage server health and lifecycle.

### GET /admin/health

Health check endpoint.

**Response:**
```json
{
  "status": "UP",
  "timestamp": "2026-01-05T12:34:56.789Z"
}
```

### GET /admin/ready

Readiness check - indicates if the server has completed scanning.

**Response:**
```json
{
  "ready": true,
  "status": "READY",
  "project": "my-project"
}
```

### GET /admin/info

Server information including version, uptime, and configuration.

**Response:**
```json
{
  "version": "0.1.0",
  "apiVersion": "v1",
  "projectPath": "/path/to/project",
  "projectName": "my-project",
  "port": 8080,
  "host": "127.0.0.1",
  "status": "READY",
  "startedAt": "2026-01-05T12:00:00.000Z",
  "uptime": "5m 30s",
  "lastActivityAt": "2026-01-05T12:05:00.000Z",
  "idleDuration": "30s",
  "idleTimeout": "30m"
}
```

### POST /admin/activity

Touch activity to reset the idle timer. Used by the CLI to keep the server alive.

**Response:**
```json
{
  "lastActivityAt": "2026-01-05T12:05:30.000Z"
}
```

### POST /admin/shutdown

Graceful shutdown (localhost only).

**Response:**
```json
{
  "message": "Shutting down..."
}
```

---

## Project Endpoints

### GET /api/v1/project

Get project information.

**Response:**
```json
{
  "name": "my-project",
  "path": "/path/to/project",
  "status": "READY",
  "classCount": 150,
  "handlerCount": 24,
  "scannedAt": "2026-01-05T12:00:05.000Z"
}
```

### POST /api/v1/project/refresh

Trigger a refresh of the project scan (after code changes).

**Response:**
```json
{
  "name": "my-project",
  "path": "/path/to/project",
  "status": "LOADING"
}
```

---

## Analysis Endpoints

### GET /api/v1/stats

Get scan statistics for the codebase.

**Response:**
```json
{
  "projectClassCount": 150,
  "libraryClassCount": 2500,
  "jdkClassCount": 8000,
  "projectInterfaceCount": 25,
  "projectAbstractClassCount": 10,
  "projectEnumCount": 8,
  "projectAnnotationCount": 3,
  "projectMethodCount": 1200,
  "projectFieldCount": 450,
  "classpathResolvedBy": "GradleToolingAPI",
  "classpathEntryCount": 85,
  "scanDurationMs": 1250,
  "scannedAt": "2026-01-05T12:00:05.000Z"
}
```

---

### GET /api/v1/classes

List classes with optional filtering and pagination.

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `package` | string | - | Filter by package pattern (supports `*` wildcard) |
| `name` | string | - | Filter by class name pattern (supports `*` wildcard) |
| `annotation` | string | - | Filter to classes with this annotation |
| `extends` | string | - | Filter to classes extending this class |
| `implements` | string | - | Filter to classes implementing this interface |
| `interfaces` | boolean | `false` | Only show interfaces |
| `includeLibraries` | boolean | `false` | Include library classes |
| `page` | int | `0` | Page number (0-based) |
| `size` | int | `50` | Page size |

**Example:**
```
GET /api/v1/classes?package=com.example.api.*&implements=ratpack.handling.Handler
```

**Response:**
```json
{
  "classes": [
    {
      "fqn": "com.example.api.UserHandler",
      "simpleName": "UserHandler",
      "packageName": "com.example.api",
      "source": "PROJECT",
      "isInterface": false,
      "isAbstract": false,
      "isEnum": false,
      "isAnnotation": false,
      "methodCount": 5,
      "fieldCount": 3
    }
  ],
  "totalCount": 24,
  "page": 0,
  "pageSize": 50,
  "totalPages": 1,
  "appliedFilter": {
    "packagePattern": "com.example.api.*",
    "namePattern": null,
    "source": "PROJECT",
    "hasAnnotation": null,
    "extendsClass": null,
    "implementsInterface": "ratpack.handling.Handler"
  }
}
```

---

### GET /api/v1/classes/{fqn}

Get full details for a specific class.

**Path Parameters:**

| Parameter | Description |
|-----------|-------------|
| `fqn` | Fully qualified class name (e.g., `com.example.UserHandler`) |

**Example:**
```
GET /api/v1/classes/com.example.api.UserHandler
```

**Response:**
```json
{
  "classInfo": {
    "name": {
      "fqn": "com.example.api.UserHandler",
      "simpleName": "UserHandler",
      "packageName": "com.example.api"
    },
    "source": "PROJECT",
    "visibility": "PUBLIC",
    "isInterface": false,
    "isAbstract": false,
    "isFinal": false,
    "isEnum": false,
    "isAnnotation": false,
    "isSynthetic": false,
    "superclass": "java.lang.Object",
    "interfaces": ["ratpack.handling.Handler"],
    "annotations": [
      {
        "type": "javax.inject.Singleton",
        "parameters": {}
      }
    ],
    "methods": [
      {
        "name": "handle",
        "visibility": "PUBLIC",
        "returnType": "void",
        "parameters": [
          {
            "name": "ctx",
            "type": "ratpack.handling.Context",
            "annotations": []
          }
        ],
        "annotations": [],
        "isStatic": false,
        "isAbstract": false,
        "isFinal": false,
        "isSynthetic": false
      }
    ],
    "fields": [
      {
        "name": "userService",
        "visibility": "PRIVATE",
        "type": "com.example.service.UserService",
        "annotations": [
          {
            "type": "javax.inject.Inject",
            "parameters": {}
          }
        ],
        "isStatic": false,
        "isFinal": true
      }
    ]
  }
}
```

**Error Response (404):**
```json
{
  "code": 404,
  "type": "NotFound",
  "message": "Class not found: com.example.Unknown"
}
```

---

### GET /api/v1/implementations/{fqn}

Find all implementations of an interface or subclasses of a class.

**Path Parameters:**

| Parameter | Description |
|-----------|-------------|
| `fqn` | Fully qualified interface or class name |

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `includeLibraries` | boolean | `false` | Include library classes |

**Example:**
```
GET /api/v1/implementations/ratpack.handling.Handler
```

**Response:**
```json
{
  "targetClass": "ratpack.handling.Handler",
  "directImplementations": [
    {
      "fqn": "com.example.api.UserHandler",
      "simpleName": "UserHandler",
      "packageName": "com.example.api",
      "source": "PROJECT",
      "isInterface": false,
      "isAbstract": false,
      "isEnum": false,
      "isAnnotation": false,
      "methodCount": 5,
      "fieldCount": 3
    }
  ],
  "indirectImplementations": [],
  "totalCount": 24
}
```

---

### GET /api/v1/hierarchy/{fqn}

Get the class hierarchy for a class, including parent chain, interfaces, and children.

**Path Parameters:**

| Parameter | Description |
|-----------|-------------|
| `fqn` | Fully qualified class name |

**Example:**
```
GET /api/v1/hierarchy/com.example.api.UserHandler
```

**Response:**
```json
{
  "targetClass": "com.example.api.UserHandler",
  "hierarchy": {
    "classFqn": "com.example.api.UserHandler",
    "simpleName": "UserHandler",
    "source": "PROJECT",
    "isInterface": false,
    "parent": {
      "classFqn": "java.lang.Object",
      "simpleName": "Object",
      "source": "JDK",
      "isInterface": false,
      "parent": null,
      "interfaces": [],
      "children": []
    },
    "interfaces": [
      {
        "classFqn": "ratpack.handling.Handler",
        "simpleName": "Handler",
        "source": "LIBRARY",
        "isInterface": true,
        "parent": null,
        "interfaces": [],
        "children": []
      }
    ],
    "children": []
  }
}
```

---

### GET /api/v1/dependencies/{fqn}

Get dependencies for a class (both incoming and outgoing).

**Path Parameters:**

| Parameter | Description |
|-----------|-------------|
| `fqn` | Fully qualified class name |

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `includeLibraries` | boolean | `false` | Include library classes |

**Example:**
```
GET /api/v1/dependencies/com.example.api.UserHandler
```

**Response:**
```json
{
  "targetClass": "com.example.api.UserHandler",
  "outgoing": [
    {
      "classFqn": "com.example.service.UserService",
      "dependencyType": "FIELD_TYPE",
      "source": "PROJECT",
      "location": "userService"
    },
    {
      "classFqn": "ratpack.handling.Context",
      "dependencyType": "METHOD_PARAMETER",
      "source": "LIBRARY",
      "location": "handle"
    }
  ],
  "incoming": [
    {
      "classFqn": "com.example.config.AppModule",
      "dependencyType": "TYPE_REFERENCE",
      "source": "PROJECT",
      "location": null
    }
  ]
}
```

**Dependency Types:**

| Type | Description |
|------|-------------|
| `EXTENDS` | Class extends another class |
| `IMPLEMENTS` | Class implements an interface |
| `FIELD_TYPE` | Field type reference |
| `METHOD_RETURN_TYPE` | Method return type |
| `METHOD_PARAMETER` | Method parameter type |
| `TYPE_REFERENCE` | Other type reference |

---

### GET /api/v1/annotations/usages/{fqn}

Find all classes using a specific annotation.

**Path Parameters:**

| Parameter | Description |
|-----------|-------------|
| `fqn` | Fully qualified annotation name |

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `includeLibraries` | boolean | `false` | Include library classes |

**Example:**
```
GET /api/v1/annotations/usages/javax.inject.Singleton
```

**Response:**
```json
{
  "annotationFqn": "javax.inject.Singleton",
  "usages": [
    {
      "fqn": "com.example.service.UserService",
      "simpleName": "UserService",
      "packageName": "com.example.service",
      "source": "PROJECT",
      "isInterface": false,
      "isAbstract": false,
      "isEnum": false,
      "isAnnotation": false,
      "methodCount": 10,
      "fieldCount": 5
    }
  ],
  "totalCount": 15
}
```

---

### GET /api/v1/methods

Search methods across all classes.

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `name` | string | - | Filter by method name pattern (supports `*` wildcard) |
| `returnType` | string | - | Filter by return type FQN |
| `annotation` | string | - | Filter to methods with this annotation |
| `inClass` | string | - | Filter by containing class FQN |
| `inPackage` | string | - | Filter by containing package pattern |
| `includeLibraries` | boolean | `false` | Include library classes |
| `page` | int | `0` | Page number (0-based) |
| `size` | int | `50` | Page size |

**Example:**
```
GET /api/v1/methods?returnType=ratpack.exec.Promise&inPackage=com.example.*
```

**Response:**
```json
{
  "methods": [
    {
      "classFqn": "com.example.service.UserService",
      "classSimpleName": "UserService",
      "classSource": "PROJECT",
      "method": {
        "name": "getUser",
        "visibility": "PUBLIC",
        "returnType": "ratpack.exec.Promise",
        "parameters": [
          {
            "name": "userId",
            "type": "java.lang.String",
            "annotations": []
          }
        ],
        "annotations": [],
        "isStatic": false,
        "isAbstract": false,
        "isFinal": false,
        "isSynthetic": false
      }
    }
  ],
  "totalCount": 45,
  "page": 0,
  "pageSize": 50,
  "totalPages": 1
}
```

---

## Source Endpoints

These endpoints provide source code retrieval for classes.

### GET /api/v1/source/{fqn}

Get source code for a class.

**Path Parameters:**

| Parameter | Description |
|-----------|-------------|
| `fqn` | Fully qualified class name |

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `format` | string | `full` | Output format: `full`, `stub`, `signatures`, `javadoc` |
| `visibility` | string | `all` | Filter by visibility: `all`, `public`, `protected` |
| `lang` | string | - | Stub language: `java`, `kotlin` (only applies to stub format) |
| `allowDecompilation` | boolean | `true` | Allow decompilation fallback when source unavailable |
| `forceRefresh` | boolean | `false` | Force re-download of source JAR |

**Format Options:**

| Format | Description | Source Required? |
|--------|-------------|------------------|
| `full` | Complete source code | Yes |
| `stub` | Signatures with placeholder bodies | No (uses bytecode) |
| `signatures` | Just declarations | No (uses bytecode) |
| `javadoc` | Signatures + doc comments | Yes |

**Example:**
```
GET /api/v1/source/com.google.common.collect.ImmutableList?format=stub&lang=kotlin
```

**Response:**
```json
{
  "fqn": "com.google.common.collect.ImmutableList",
  "source": "package com.google.common.collect\n\nabstract class ImmutableList<E> : ...",
  "sourceFile": null,
  "language": "KOTLIN",
  "startLine": null,
  "endLine": null,
  "sourceOrigin": "SOURCE_JAR",
  "mavenCoordinates": "com.google.guava:guava:32.1.3-jre",
  "isDecompiled": false,
  "format": "STUB"
}
```

**Source Origins:**

| Origin | Description |
|--------|-------------|
| `PROJECT_SOURCE` | From project source roots |
| `SOURCE_JAR` | From library -sources.jar |
| `DECOMPILED` | From bytecode decompilation |
| `JDK_SOURCE` | From JDK src.zip |

**Error Response (404):**
```json
{
  "code": 404,
  "type": "NotFound",
  "message": "Class not found: com.example.Unknown"
}
```

---

### GET /api/v1/source/{fqn}/method/{methodName}

Get source code for a specific method.

**Path Parameters:**

| Parameter | Description |
|-----------|-------------|
| `fqn` | Fully qualified class name |
| `methodName` | Method name |

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `paramTypes` | string | - | Comma-separated parameter types to disambiguate overloads |
| `context` | int | `0` | Number of context lines before/after method |

**Example:**
```
GET /api/v1/source/com.example.UserHandler/method/handle
```

**Response:**
```json
{
  "fqn": "com.example.UserHandler",
  "methodName": "handle",
  "source": "public void handle(Context ctx) {\n    ...\n}",
  "sourceFile": "/path/to/UserHandler.java",
  "language": "JAVA",
  "startLine": 25,
  "endLine": 35
}
```

---

## Lint Endpoints

These endpoints provide Kotlin linting and formatting via ktlint.

### POST /api/v1/ktlint/lint/file

Lint a single Kotlin file.

**Request Body:**
```json
{
  "filePath": "/path/to/file.kt"
}
```

**Response:**
```json
{
  "filePath": "/path/to/file.kt",
  "errors": [
    {
      "line": 1,
      "col": 17,
      "ruleId": "standard:spacing",
      "detail": "Missing space before '{'",
      "canBeAutoCorrected": true
    }
  ],
  "errorCount": 1,
  "durationMs": 45
}
```

---

### POST /api/v1/ktlint/lint/project

Lint all Kotlin files in the project.

**Request Body:**
```json
{
  "pattern": "*.kt",
  "includeTests": true
}
```

All fields are optional.

**Response:**
```json
{
  "projectPath": "/path/to/project",
  "fileResults": [
    {
      "filePath": "/path/to/project/src/Bad.kt",
      "errors": [...],
      "errorCount": 3
    }
  ],
  "filesScanned": 50,
  "filesWithErrors": 3,
  "totalErrorCount": 12,
  "durationMs": 250
}
```

---

### POST /api/v1/ktlint/format/file

Format a single Kotlin file.

**Request Body:**
```json
{
  "filePath": "/path/to/file.kt",
  "writeToFile": false
}
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `filePath` | string | required | Absolute path to file |
| `writeToFile` | boolean | `false` | Whether to write changes to disk |

**Response:**
```json
{
  "filePath": "/path/to/file.kt",
  "formattedContent": "formatted code here...",
  "hasChanges": true,
  "remainingErrors": [],
  "durationMs": 30
}
```

When `writeToFile` is `true`, `formattedContent` will be `null` and the file is modified in place.

---

### POST /api/v1/ktlint/format/project

Format all Kotlin files in the project.

**Request Body:**
```json
{
  "pattern": "*.kt",
  "includeTests": true,
  "dryRun": false
}
```

All fields are optional.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `pattern` | string | `null` | Glob pattern to filter files |
| `includeTests` | boolean | `true` | Include test files |
| `dryRun` | boolean | `false` | If true, don't modify files |

**Response:**
```json
{
  "projectPath": "/path/to/project",
  "filesFormatted": [
    "/path/to/project/src/File1.kt",
    "/path/to/project/src/File2.kt"
  ],
  "filesScanned": 50,
  "filesWithChanges": 2,
  "durationMs": 500
}
```

---

## Error Responses

All endpoints return consistent error responses:

```json
{
  "code": 400,
  "type": "BadRequest",
  "message": "Class FQN is required"
}
```

**Common Error Codes:**

| Code | Type | Description |
|------|------|-------------|
| 400 | BadRequest | Invalid request parameters |
| 404 | NotFound | Resource not found |
| 503 | ServiceUnavailable | Scan not completed yet |

---

## Ratpack Analysis Endpoints

These endpoints provide Ratpack-specific analysis for migration planning.

### GET /api/v1/ratpack/handlers

List all Ratpack handlers.

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `type` | string | - | Filter by handler type (HANDLER, CHAIN_ACTION, INLINE_HANDLER, GROOVY_HANDLER) |
| `tier` | string | - | Filter by complexity tier (LOW, MEDIUM, HIGH, CRITICAL) |
| `includeLibraries` | boolean | `false` | Include library handlers |

**Response:**
```json
{
  "handlers": [
    {
      "fqn": "com.example.UserHandler",
      "simpleName": "UserHandler",
      "packageName": "com.example",
      "handlerType": "HANDLER",
      "source": "PROJECT",
      "complexityScore": 35,
      "complexityTier": "MEDIUM",
      "promiseOperationCount": 5,
      "usesBlocking": true
    }
  ],
  "totalCount": 24,
  "appliedFilters": {
    "handlerType": null,
    "tier": null
  }
}
```

---

### GET /api/v1/ratpack/handlers/{fqn}

Get detailed information about a handler.

**Response:**
```json
{
  "handler": {
    "fqn": "com.example.UserHandler",
    "simpleName": "UserHandler",
    "packageName": "com.example",
    "handlerType": "HANDLER",
    "source": "PROJECT",
    "superclass": "java.lang.Object",
    "interfaces": ["ratpack.handling.Handler"],
    "handlerMethods": [...],
    "allMethods": [...],
    "promiseAnalysis": {
      "classFqn": "com.example.UserHandler",
      "totalOperationCount": 5,
      "usesBlocking": true,
      "usesAsync": false,
      "usesFork": false,
      "usesParallelBatch": false,
      "maxChainDepth": 3,
      "promiseReturningMethods": ["getUser"]
    },
    "complexity": {
      "classFqn": "com.example.UserHandler",
      "score": 35,
      "tier": "MEDIUM",
      "estimatedHours": 4.0,
      "factors": [...],
      "migrationNotes": ["Contains Blocking.get()"]
    },
    "injectedDependencies": [
      {
        "name": "userService",
        "typeFqn": "com.example.UserService",
        "injectionType": "CONSTRUCTOR"
      }
    ]
  }
}
```

---

### GET /api/v1/ratpack/promises

Get project-wide Promise usage summary.

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `includeLibraries` | boolean | `false` | Include library classes |

**Response:**
```json
{
  "summary": {
    "classesUsingPromises": 15,
    "blockingGetCount": 23,
    "promiseAsyncCount": 8,
    "executionForkCount": 3,
    "parallelBatchCount": 1,
    "operatorCount": 45,
    "operationBreakdown": {
      "BLOCKING_GET": 23,
      "PROMISE_MAP": 15,
      "PROMISE_FLAT_MAP": 10
    },
    "topComplexClasses": [...]
  }
}
```

---

### GET /api/v1/ratpack/promises/search

Search for classes with specific Promise usage patterns.

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `usesBlocking` | boolean | - | Filter by Blocking usage |
| `usesAsync` | boolean | - | Filter by async usage |
| `usesFork` | boolean | - | Filter by fork usage |
| `minOperations` | int | `0` | Minimum operation count |

**Response:**
```json
{
  "results": [
    {
      "classFqn": "com.example.UserService",
      "totalOperationCount": 8,
      "usesBlocking": true,
      "usesAsync": false,
      "usesFork": false,
      "usesParallelBatch": false,
      "maxChainDepth": 3
    }
  ],
  "totalCount": 5
}
```

---

### GET /api/v1/ratpack/promises/{fqn}

Get Promise usage for a specific class.

---

### GET /api/v1/ratpack/complexity

Get project-wide complexity summary.

**Response:**
```json
{
  "summary": {
    "totalHandlers": 24,
    "tierBreakdown": {
      "LOW": 10,
      "MEDIUM": 8,
      "HIGH": 4,
      "CRITICAL": 2
    },
    "totalEstimatedHours": 120.5,
    "averageScore": 42.3,
    "migrationOrder": [...]
  }
}
```

---

### GET /api/v1/ratpack/complexity/{fqn}

Get complexity score for a specific class.

**Response:**
```json
{
  "complexity": {
    "classFqn": "com.example.UserHandler",
    "score": 35,
    "tier": "MEDIUM",
    "estimatedHours": 4.0,
    "factors": [
      {
        "name": "Blocking Usage",
        "description": "Uses Blocking.get() which needs careful migration",
        "points": 15,
        "maxPoints": 15,
        "details": "Blocking operations need conversion to coroutines"
      }
    ],
    "migrationNotes": [
      "Contains Blocking.get() - requires conversion to non-blocking pattern"
    ],
    "migrationPriority": 2,
    "blockedBy": []
  }
}
```

---

### GET /api/v1/ratpack/migration-order

Get suggested migration order.

**Response:**
```json
{
  "order": [
    {
      "classFqn": "com.example.SimpleHandler",
      "simpleName": "SimpleHandler",
      "tier": "LOW",
      "estimatedHours": 1.0,
      "order": 1,
      "reason": "Quick win - simple migration"
    }
  ],
  "totalCount": 24,
  "totalEstimatedHours": 120.5
}
```

---

### GET /api/v1/ratpack/modules

List all Guice modules.

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `includeLibraries` | boolean | `false` | Include library modules |

**Response:**
```json
{
  "modules": [
    {
      "fqn": "com.example.AppModule",
      "simpleName": "AppModule",
      "packageName": "com.example",
      "moduleType": "ABSTRACT_MODULE",
      "bindingCount": 5,
      "providesMethodCount": 3
    }
  ],
  "totalCount": 4
}
```

---

### GET /api/v1/ratpack/modules/{fqn}

Get detailed information about a Guice module.

**Response:**
```json
{
  "module": {
    "fqn": "com.example.AppModule",
    "simpleName": "AppModule",
    "packageName": "com.example",
    "moduleType": "ABSTRACT_MODULE",
    "configType": null,
    "bindings": [...],
    "providesMethods": [
      {
        "methodName": "provideUserService",
        "providesType": "com.example.UserService",
        "scope": "com.google.inject.Singleton",
        "intoSet": false,
        "intoMap": false,
        "dependencies": []
      }
    ],
    "installedModules": []
  }
}
```

---

### GET /api/v1/ratpack/bindings/{fqn}

Find all bindings for a specific type.

**Response:**
```json
{
  "typeFqn": "com.example.UserService",
  "bindings": [
    {
      "moduleFqn": "com.example.AppModule",
      "binding": {
        "boundType": "com.example.UserService",
        "toType": null,
        "scope": "com.google.inject.Singleton",
        "isMultibinding": false,
        "bindingSource": "PROVIDES"
      }
    }
  ],
  "totalCount": 1
}
```

---

### GET /api/v1/ratpack/antipatterns

Get project-wide anti-pattern summary.

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `severity` | string | - | Filter by severity (INFO, WARNING, ERROR, CRITICAL) |
| `type` | string | - | Filter by anti-pattern type |
| `includeLibraries` | boolean | `false` | Include library classes |

**Anti-Pattern Types:**
- `BLOCKING_JDBC` - JDBC calls without Blocking.get() wrapper
- `THREAD_SLEEP` - Thread.sleep() calls blocking the event loop
- `SYNCHRONOUS_FILE_IO` - Blocking file I/O operations
- `BLOCKING_HTTP_CLIENT` - Using Apache HttpClient or java.net.URL
- `CONSOLE_LOGGING` - Direct System.out/err usage
- `SWALLOWED_EXCEPTION` - Catching and swallowing exceptions

**Response:**
```json
{
  "summary": {
    "instances": [
      {
        "type": "BLOCKING_JDBC",
        "severity": "CRITICAL",
        "classFqn": "com.example.UserHandler",
        "methodName": null,
        "confidence": 0.8,
        "reason": "JDBC types are used without visible Blocking.get() usage.",
        "recommendation": "Wrap JDBC calls in Blocking.get { ... }",
        "fixExample": "..."
      }
    ],
    "countByType": {"BLOCKING_JDBC": 2, "BLOCKING_HTTP_CLIENT": 1},
    "countBySeverity": {"CRITICAL": 2, "ERROR": 1},
    "worstOffenders": [
      {"classFqn": "com.example.UserHandler", "count": 2, "criticalCount": 1, "errorCount": 1}
    ],
    "totalCount": 3
  },
  "appliedFilters": {
    "severity": null,
    "type": null,
    "includeLibraries": false
  }
}
```

---

### GET /api/v1/ratpack/antipatterns/{fqn}

Get anti-patterns for a specific class.

**Response:**
```json
{
  "classFqn": "com.example.UserHandler",
  "antiPatterns": [
    {
      "type": "BLOCKING_JDBC",
      "severity": "CRITICAL",
      "classFqn": "com.example.UserHandler",
      "methodName": null,
      "confidence": 0.8,
      "reason": "JDBC types are used without visible Blocking.get() usage.",
      "recommendation": "Wrap JDBC calls in Blocking.get { ... }",
      "fixExample": "..."
    }
  ],
  "totalCount": 1
}
```

---

### GET /api/v1/ratpack/routes

Get all routes in the application.

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `includeLibraries` | boolean | `false` | Include library classes |

**Response:**
```json
{
  "summary": {
    "totalRoutes": 5,
    "routesByMethod": {"GET": 3, "POST": 1, "DELETE": 1},
    "routes": [
      {
        "method": "GET",
        "pathPattern": "/users",
        "handlerFqn": "com.example.ListUsersHandler",
        "handlerSimpleName": "ListUsersHandler",
        "chainFqn": "com.example.UsersChain",
        "pathParameters": [],
        "isPrefix": false,
        "nestedRoutes": []
      }
    ],
    "chainClasses": [
      {
        "fqn": "com.example.UsersChain",
        "simpleName": "UsersChain",
        "routeCount": 4,
        "pathPrefix": "/users"
      }
    ],
    "uniquePaths": 4
  }
}
```

---

### GET /api/v1/ratpack/routes/tree

Get routes as a tree structure.

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `includeLibraries` | boolean | `false` | Include library classes |

**Response:**
```json
{
  "tree": {
    "segment": "",
    "fullPath": "/",
    "routes": [],
    "children": [
      {
        "segment": "users",
        "fullPath": "/users",
        "routes": [
          {"method": "GET", "pathPattern": "/users", "handlerSimpleName": "ListHandler"}
        ],
        "children": [
          {
            "segment": ":id",
            "fullPath": "/users/:id",
            "routes": [
              {"method": "GET", "pathPattern": "/users/:id", "handlerSimpleName": "GetHandler"}
            ],
            "children": []
          }
        ]
      }
    ]
  }
}
```

---

### GET /api/v1/ratpack/routes/spring

Get Spring @RequestMapping equivalents for all routes.

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `includeLibraries` | boolean | `false` | Include library classes |

**Response:**
```json
{
  "mappings": [
    {
      "ratpackRoute": {
        "method": "GET",
        "pathPattern": "/users/:id",
        "handlerSimpleName": "GetUserHandler"
      },
      "springAnnotation": "@GetMapping(\"/users/{id}\")",
      "methodSignature": "fun getUser(@PathVariable id: String): ResponseEntity<*>",
      "notes": ["Contains 1 path parameter(s)"]
    }
  ],
  "totalCount": 5
}
```

---

## Source Classification

Classes are classified by source:

| Source | Description |
|--------|-------------|
| `PROJECT` | Classes from the project's own source code |
| `LIBRARY` | Classes from third-party dependencies |
| `JDK` | Classes from the Java standard library |

By default, only `PROJECT` classes are returned. Use `includeLibraries=true` to include `LIBRARY` and `JDK` classes.
