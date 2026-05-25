---
name: codelens-kotlin-linting
description: |
  Use when the user's goal is checking or fixing the style/formatting of existing Kotlin
  (.kt) code. Triggers: asking whether Kotlin follows its coding conventions or a team style
  guide; formatting or reformatting a file, directory, or whole project; finding or fixing
  style violations (indentation, import ordering, wildcard imports, blank lines, trailing
  commas, max line length); making a ktlint or lint gate pass before a commit/PR or in CI;
  or diagnosing why a ktlint check is failing. Applies even when "ktlint" isn't named — e.g.
  "does this Kotlin match the style guide?", "check MyViewModel.kt for style violations," or
  "auto-format my changed .kt before the PR." Do NOT use for Java or other-language
  formatting, detekt static analysis / code smells / complexity, writing or generating new
  Kotlin, navigating or finding Kotlin types, or upgrading ktlint/Gradle versions.
---

# Kotlin Linting

This skill provides Kotlin code style checking and formatting using ktlint.

## When to Use

- Check Kotlin files for style violations
- Auto-format Kotlin code to match style guidelines
- Preview formatting changes before applying
- Ensure code consistency across a project

## Prerequisites

Ensure the CodeLens server is running for your project:

```bash
codelens start --project /path/to/project
```

## Checking Code Style

### Check Single File

```bash
codelens lint check <file-path>
```

**Example:**
```bash
codelens lint check src/main/kotlin/com/example/MyService.kt
```

**Output:**
```
src/main/kotlin/com/example/MyService.kt
  Line 15:1  Missing blank line before declaration (standard:blank-line-before-declaration)
  Line 23:5  Unexpected indentation (4) (should be 8) (standard:indent)
  Line 45:80 Exceeded max line length (100) (standard:max-line-length)

Found 3 violations in 1 file
```

### Check Entire Project

```bash
codelens lint check
```

**Options:**
- `--pattern <glob>` - Filter files by pattern (e.g., `**/handlers/**/*.kt`)
- `--include-tests` - Include test files (excluded by default in some configs)

**Examples:**
```bash
# All Kotlin files
codelens lint check

# Only files in specific package
codelens lint check --pattern "**/handlers/**/*.kt"

# Include test files
codelens lint check --include-tests
```

## Formatting Code

### Format Single File

```bash
codelens lint format <file-path>
```

Applies auto-fixes directly to the file.

### Format with Dry Run

Preview changes without modifying files:

```bash
codelens lint format <file-path> --dry-run
```

**Output:**
```
Would format: src/main/kotlin/com/example/MyService.kt
--- Before
+++ After
@@ -15,6 +15,7 @@
 class MyService {
+
     fun process() {
```

### Format Entire Project

```bash
codelens lint format
```

**Options:**
- `--pattern <glob>` - Filter files by pattern
- `--include-tests` - Include test files
- `--dry-run` - Preview changes without applying

**Examples:**
```bash
# Format all Kotlin files
codelens lint format

# Preview project-wide changes
codelens lint format --dry-run

# Format specific directory
codelens lint format --pattern "**/api/**/*.kt"
```

## Understanding Output

### Violation Format

```
<file-path>
  Line <line>:<column>  <message> (<rule-id>)
```

### Common Rule Categories

| Category | Examples |
|----------|----------|
| `standard:indent` | Indentation issues |
| `standard:max-line-length` | Lines exceeding limit |
| `standard:no-wildcard-imports` | Star imports |
| `standard:no-unused-imports` | Unused imports |
| `standard:blank-line-before-declaration` | Missing blank lines |
| `standard:trailing-comma` | Trailing comma requirements |

### Exit Codes

| Code | Meaning |
|------|---------|
| 0 | No violations found (check) or formatting successful (format) |
| 1 | Violations found (check) or formatting made changes (format) |

## Auto-Fixable vs Manual Fixes

Some violations can be auto-fixed, others require manual intervention:

**Auto-fixable:**
- Indentation
- Blank lines
- Import ordering
- Trailing commas
- Whitespace issues

**Manual fixes required:**
- Max line length (may require logic changes)
- Some naming conventions
- Complex structural issues

The output indicates which violations are auto-fixable:

```
Line 15:1  Missing blank line (standard:blank-line-before-declaration) [auto-fix available]
Line 23:100 Line too long (standard:max-line-length) [manual fix required]
```

## Workflow Integration

### Pre-Commit Check

```bash
# Check changed Kotlin files
codelens lint check $(git diff --name-only --cached -- '*.kt')
```

### CI Pipeline

```bash
# Fail if any violations
codelens lint check || exit 1
```

### Format Before Commit

```bash
# Format all Kotlin files
codelens lint format

# Stage formatted files
git add -u
```

## Configuration

ktlint uses `.editorconfig` for configuration. Common settings:

```ini
# .editorconfig
[*.kt]
max_line_length = 120
indent_size = 4
insert_final_newline = true

[*.{kt,kts}]
ktlint_code_style = android_studio  # or intellij_idea, ktlint_official
```

## Tips

- Run `--dry-run` first to preview changes before formatting
- Use `--pattern` to focus on specific areas during incremental adoption
- Auto-fix handles most issues; review manual-fix items individually
- Configure `.editorconfig` to match team preferences

## External References

- [ktlint Rules](https://pinterest.github.io/ktlint/latest/rules/standard/)
- [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)

## Related Skills

- `codelens-jvm-analysis` - Find Kotlin classes to lint
- `codelens-source-lookup` - View Kotlin source code
