# Route Identification Guide for Ratpack Projects

This guide explains how to use CodeLens to identify HTTP routes in Ratpack applications and what additional analysis is required for complete route discovery.

## Overview

Ratpack defines routes programmatically in `Action<Chain>` implementations via method calls like `chain.get()`, `chain.post()`, and `chain.prefix()`. CodeLens analyzes compiled bytecode to extract route information, but has limitations with routes defined inside Java lambdas.

**What CodeLens detects accurately:**
- Direct routes: `chain.get("path", Handler.class)`
- Prefix entry points: `chain.prefix("path", SomeApi.class)`
- Handler class references

**What CodeLens cannot detect:**
- Routes inside inline lambdas: `chain.prefix("path", c -> c.get(...))`
- HTTP methods inside `byMethod()` callbacks
- Routes in version-specific methods called via `when()`

## Recommended Workflow

### Step 1: List All Routes

```bash
codelens routes list
```

This returns the detected route structure. Example output:
```
GET    /ping
GET    /config
ALL    /locationgroups/:locationGroupId/locations/:locationId/devices  [DevicesApi]
ALL    /locationgroups/:locationGroupId/locations                      [LocationsApi]
ALL    /locationgroups                                                 [LocationGroupsApi]
```

**Interpretation:**
- `GET`, `POST`, etc. with specific paths are complete route definitions
- `ALL` typically indicates either:
  - A middleware handler (if path is `/`)
  - A prefix route whose child routes need expansion

### Step 2: List All Handlers

```bash
codelens handlers list
```

This returns all classes implementing `ratpack.handling.Handler`. Handler class names typically encode the HTTP method and purpose:

```
com.example.api.DeviceStateGetHandler
com.example.api.DeviceStateUpdateHandler
com.example.api.DeviceStatePatchHandler
com.example.api.DeviceStateDeleteHandler
com.example.api.LocationInstallersListHandler
com.example.api.LocationInstallersGrantHandler
com.example.api.LocationInstallersRevokeHandler
```

**Naming conventions:**
| Handler Name Pattern | HTTP Method | Operation |
|---------------------|-------------|-----------|
| `*GetHandler` | GET | Retrieve single item |
| `*ListHandler` | GET | Retrieve collection |
| `*CreateHandler` | POST | Create new item |
| `*UpdateHandler` | PUT | Full update |
| `*PatchHandler` | PATCH | Partial update |
| `*DeleteHandler` | DELETE | Remove item |
| `*GrantHandler` | POST | Grant permission |
| `*RevokeHandler` | POST | Revoke permission |

### Step 3: Find Action Chain Implementations

```bash
codelens classes list --implements "ratpack.func.Action"
```

This lists all `Action<Chain>` classes that define route structures. Each of these classes contains an `execute(Chain)` method with route definitions.

### Step 4: Correlate Routes with Handlers

For each prefix route detected in Step 1, find handlers that belong to that route group by examining the package structure or class naming.

**Example correlation:**

Given route: `ALL /locationgroups/:locationGroupId/locations/:locationId/devices [DevicesApi]`

Find handlers in the same package as `DevicesApi`:
```bash
codelens handlers list | grep -i device
```

Output:
```
com.example.devicestate.api.v1.DeviceStateGetHandler
com.example.devicestate.api.v1.DeviceStateUpdateHandler
com.example.devicestate.api.v1.DeviceStatePatchHandler
com.example.devicestate.api.v1.DeviceStateDeleteHandler
```

**Inferred complete routes:**
```
GET    /locationgroups/:lgId/locations/:locId/devices/:deviceId
PUT    /locationgroups/:lgId/locations/:locId/devices/:deviceId
PATCH  /locationgroups/:lgId/locations/:locId/devices/:deviceId
DELETE /locationgroups/:lgId/locations/:locId/devices/:deviceId
```

## Route Expansion Algorithm

When you encounter an `ALL` prefix route, expand it using this process:

1. **Identify the handler class** from the route output (e.g., `[DevicesApi]`)

2. **Find related handlers** by package proximity or naming convention:
   ```bash
   codelens handlers list --package "com.example.devicestate.api"
   ```

3. **Infer HTTP methods** from handler names using the naming conventions table above

4. **Determine path parameters** by examining:
   - The prefix path from CodeLens output
   - Common REST patterns (`:id` suffixes typically indicate single-resource endpoints)
   - Handler names that suggest hierarchy (e.g., `LocationGroupStatisticsGetHandler` suggests `/stats` under a location group)

5. **Check for nested prefixes** by looking for additional `Action<Chain>` classes in the same package

## Common Ratpack Patterns

### Pattern 1: Resource CRUD
```
prefix("resources", ResourceApi.class)
```
Typically expands to:
```
GET    /resources           (list)
POST   /resources           (create)
GET    /resources/:id       (get)
PUT    /resources/:id       (update)
DELETE /resources/:id       (delete)
```

### Pattern 2: Nested Resources
```
prefix("parents/:parentId/children", ChildApi.class)
```
Handlers follow parent context:
```
GET    /parents/:parentId/children
POST   /parents/:parentId/children
GET    /parents/:parentId/children/:childId
```

### Pattern 3: Action Endpoints
```
prefix("resources/:id", actions -> actions
    .prefix("grant", GrantHandler.class)
    .prefix("revoke", RevokeHandler.class))
```
Results in:
```
POST   /resources/:id/grant
POST   /resources/:id/revoke
```

### Pattern 4: Versioned APIs
Classes with version methods (`v1()`, `v2()`) define the same routes with different handlers:
```java
chain.when(ApiVersionContext.is(V1), this::v1)
     .when(ApiVersionContext.is(V2), this::v2)
```
The routes are identical across versions; only handler implementations differ.

## Verification Checklist

After route identification, verify completeness:

- [ ] All `Action<Chain>` classes have been examined
- [ ] Each prefix route has been expanded with specific HTTP methods
- [ ] Handler count matches expected route count (approximately)
- [ ] Path parameters are consistent with handler naming
- [ ] Nested chain structures have been recursively expanded

## Example: Complete Route Discovery

**Project:** sample-ratpack-app

**Step 1 - Routes output:**
```text
GET    /ping
GET    /config
ALL    /accounts/:accountId/projects/:projectId/items  [ItemsApi]
ALL    /accounts/:accountId/projects                   [ProjectsApi]
ALL    /accounts                                       [AccountsApi]
```

**Step 2 - Handlers output (filtered):**
```text
ItemGetHandler, ItemUpdateHandler, ItemPatchHandler, ItemDeleteHandler
ProjectStatisticsGetHandler, ProjectStatisticsListHandler
AccountStatisticsGetHandler, AccountStatisticsListHandler
AccountItemsListHandler, AccountReportGetHandler
```

**Step 3 - Expanded routes:**
```text
GET    /ping
GET    /config

# ItemsApi prefix
GET    /accounts/:accountId/projects/:projectId/items/:itemId
PUT    /accounts/:accountId/projects/:projectId/items/:itemId
PATCH  /accounts/:accountId/projects/:projectId/items/:itemId
DELETE /accounts/:accountId/projects/:projectId/items/:itemId

# ProjectsApi prefix
GET    /accounts/:accountId/projects/stats
GET    /accounts/:accountId/projects/:projectId/stats

# AccountsApi prefix
GET    /accounts/stats
GET    /accounts/:accountId/items
GET    /accounts/:accountId/stats
GET    /accounts/:accountId/report
```

## Limitations

1. **Lambda-defined routes**: Routes inside inline lambdas cannot be extracted from bytecode. These require source code examination.

2. **Dynamic paths**: Paths constructed at runtime (e.g., from configuration) cannot be determined statically.

3. **Conditional routes**: Routes registered conditionally based on runtime state may not be detected.

4. **HTTP method inference**: When using `byMethod()`, the specific HTTP methods are inside lambda bytecode and cannot be extracted. Use handler naming conventions to infer methods.

## Quick Reference Commands

```bash
# Full route analysis
codelens routes list

# All handlers with types
codelens handlers list

# Find chain implementations
codelens classes list --implements "ratpack.func.Action"

# Search for specific handler patterns
codelens handlers list | grep -i "handler-name-pattern"

# Get class details including methods
codelens classes get "com.example.SomeApi"
```
