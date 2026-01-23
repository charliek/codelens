# Common ktlint Rules Reference

## Indentation Rules

### `standard:indent`

Enforces consistent indentation.

**Violation:**
```kotlin
fun example() {
  val x = 1  // Wrong: 2 spaces
}
```

**Fixed:**
```kotlin
fun example() {
    val x = 1  // Correct: 4 spaces
}
```

**Auto-fixable:** Yes

---

## Import Rules

### `standard:no-wildcard-imports`

Disallows wildcard (star) imports.

**Violation:**
```kotlin
import java.util.*
```

**Fixed:**
```kotlin
import java.util.List
import java.util.Map
```

**Auto-fixable:** No (requires knowing which types are used)

---

### `standard:no-unused-imports`

Removes unused imports.

**Violation:**
```kotlin
import java.util.List  // Not used anywhere
```

**Auto-fixable:** Yes

---

## Spacing Rules

### `standard:blank-line-before-declaration`

Requires blank line before class/function declarations.

**Violation:**
```kotlin
class Foo {
    val x = 1
    fun bar() {}  // Missing blank line
}
```

**Fixed:**
```kotlin
class Foo {
    val x = 1

    fun bar() {}
}
```

**Auto-fixable:** Yes

---

### `standard:no-blank-line-before-rbrace`

No blank line before closing brace.

**Violation:**
```kotlin
fun example() {
    doSomething()

}  // Extra blank line
```

**Fixed:**
```kotlin
fun example() {
    doSomething()
}
```

**Auto-fixable:** Yes

---

### `standard:no-consecutive-blank-lines`

Maximum one consecutive blank line.

**Auto-fixable:** Yes

---

## Line Length

### `standard:max-line-length`

Enforces maximum line length (default: 140, configurable).

**Violation:**
```kotlin
val veryLongVariableName = someFunction(parameter1, parameter2, parameter3, parameter4, parameter5)
```

**Fixed:**
```kotlin
val veryLongVariableName = someFunction(
    parameter1,
    parameter2,
    parameter3,
    parameter4,
    parameter5
)
```

**Auto-fixable:** No (requires manual reformatting)

---

## Trailing Comma

### `standard:trailing-comma-on-call-site`

Requires trailing comma in multi-line argument lists.

**Violation:**
```kotlin
listOf(
    "a",
    "b",
    "c"  // Missing trailing comma
)
```

**Fixed:**
```kotlin
listOf(
    "a",
    "b",
    "c",
)
```

**Auto-fixable:** Yes

---

### `standard:trailing-comma-on-declaration-site`

Requires trailing comma in multi-line parameter lists.

**Auto-fixable:** Yes

---

## Naming Conventions

### `standard:package-name`

Package names should be lowercase.

**Violation:**
```kotlin
package com.Example.MyPackage
```

**Fixed:**
```kotlin
package com.example.mypackage
```

**Auto-fixable:** No

---

### `standard:class-naming`

Classes use PascalCase.

**Auto-fixable:** No

---

### `standard:function-naming`

Functions use camelCase.

**Auto-fixable:** No

---

## String Templates

### `standard:string-template`

Simplifies string templates.

**Violation:**
```kotlin
val s = "Hello ${name}"  // Braces unnecessary
```

**Fixed:**
```kotlin
val s = "Hello $name"
```

**Auto-fixable:** Yes

---

## Modifier Order

### `standard:modifier-order`

Enforces standard modifier order.

**Violation:**
```kotlin
final public class Foo  // Wrong order
```

**Fixed:**
```kotlin
public final class Foo
```

**Auto-fixable:** Yes

---

## Annotation Rules

### `standard:annotation`

Annotations on separate lines for declarations.

**Violation:**
```kotlin
@Inject constructor(val service: Service)
```

**Fixed:**
```kotlin
@Inject
constructor(val service: Service)
```

**Auto-fixable:** Yes

---

## Configuring Rules

In `.editorconfig`:

```ini
[*.kt]
# Disable specific rules
ktlint_standard_no-wildcard-imports = disabled

# Configure max line length
max_line_length = 120

# Configure indent
indent_size = 4
```

## Rule Categories

| Category | Auto-fixable | Examples |
|----------|--------------|----------|
| Spacing | Mostly yes | indent, blank-lines, spacing |
| Imports | Partial | no-unused (yes), no-wildcard (no) |
| Naming | No | package-name, class-naming |
| Line length | No | max-line-length |
| Style | Mostly yes | trailing-comma, modifier-order |
