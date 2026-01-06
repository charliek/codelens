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

## Source Classification

Classes are classified by source:

| Source | Description |
|--------|-------------|
| `PROJECT` | Classes from the project's own source code |
| `LIBRARY` | Classes from third-party dependencies |
| `JDK` | Classes from the Java standard library |

By default, only `PROJECT` classes are returned. Use `includeLibraries=true` to include `LIBRARY` and `JDK` classes.
