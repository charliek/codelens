# Implementation Plan: ktlint Integration for CodeLens

## Summary

Add ktlint as a warm linting/formatting capability. The `KtLintRuleEngine` will be initialized once at server startup and reused for all operations, eliminating JVM startup time (~2-5s → ~100-500ms).

---

## New Module Structure

```
server/ktlint/
├── build.gradle.kts
└── src/main/kotlin/codelens/ktlint/
    ├── KtlintProvider.kt       # Interface
    └── KtlintProviderImpl.kt   # Implementation
```

## Dependencies (libs.versions.toml)

```toml
ktlint = "1.5.0"
ktlint-rule-engine = { module = "com.pinterest.ktlint:ktlint-rule-engine", version.ref = "ktlint" }
ktlint-ruleset-standard = { module = "com.pinterest.ktlint:ktlint-ruleset-standard", version.ref = "ktlint" }
```

---

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/ktlint/lint/file` | POST | Lint single file |
| `/api/v1/ktlint/lint/project` | POST | Lint all Kotlin files |
| `/api/v1/ktlint/format/file` | POST | Format single file |
| `/api/v1/ktlint/format/project` | POST | Format all Kotlin files |

---

## CLI Commands

```bash
codelens lint check [FILE]           # Check for style issues
codelens lint check --pattern "**/*.kt"
codelens lint format [FILE]          # Auto-fix issues
codelens lint format --dry-run       # Preview changes
```

---

## Implementation Steps

### Phase 1: Gradle Setup
1. Add ktlint deps to `gradle/libs.versions.toml`
2. Create `server/ktlint/build.gradle.kts`
3. Add module to `settings.gradle.kts`

### Phase 2: Core Models
4. Create `server/core/.../model/KtlintModels.kt`
   - `LintError`, `FileLintResult`, `LintFileResponse`, `LintProjectResponse`
   - `FormatFileResponse`, `FormatProjectResponse`

### Phase 3: Provider
5. Create `KtlintProvider` interface
6. Create `KtlintProviderImpl`:
   - Initialize `KtLintRuleEngine` once (with .editorconfig support)
   - `lintFile()`, `lintProject()`, `formatFile()`, `formatProject()`

### Phase 4: Server Integration
7. Create `KtlintService` in server/app
8. Create `KtlintRoutes.kt` with POST endpoints
9. Wire into `Application.kt`

### Phase 5: CLI
10. Add client methods to `client.py`
11. Create `commands/lint.py` with Typer commands
12. Register in `main.py`

### Phase 6: Testing
13. Create `server/ktlint/src/test/kotlin/.../KtlintProviderImplTest.kt`
14. Create `cli/tests/test_lint.py`
15. Add Kotlin test fixtures with style violations to `test-fixtures/`

### Phase 7: Documentation
16. Update `docs/cli.md` with Lint Commands section
17. Update `docs/api.md` with Lint Endpoints section
18. Update `README.md` with quick reference and repo structure

---

## Key Files to Modify

| File | Change |
|------|--------|
| `gradle/libs.versions.toml` | Add ktlint dependencies |
| `settings.gradle.kts` | Include `server:ktlint` |
| `server/app/build.gradle.kts` | Depend on `:server:ktlint` |
| `server/app/.../Application.kt` | Wire KtlintService + routes |
| `cli/src/codelens_cli/main.py` | Register lint command group |
| `cli/src/codelens_cli/client.py` | Add lint/format API methods |
| `docs/cli.md` | Add Lint Commands section |
| `docs/api.md` | Add Lint Endpoints section |
| `README.md` | Update quick reference + repo structure |

## New Files to Create

| File | Purpose |
|------|---------|
| `server/ktlint/build.gradle.kts` | Module build config |
| `server/ktlint/.../KtlintProvider.kt` | Provider interface |
| `server/ktlint/.../KtlintProviderImpl.kt` | ktlint integration |
| `server/ktlint/.../KtlintProviderImplTest.kt` | Provider unit tests |
| `server/core/.../KtlintModels.kt` | Request/response models |
| `server/app/.../services/KtlintService.kt` | Service layer |
| `server/app/.../routes/KtlintRoutes.kt` | HTTP endpoints |
| `cli/src/codelens_cli/commands/lint.py` | CLI commands |
| `cli/tests/test_lint.py` | CLI command tests |
| `test-fixtures/.../BadFormatting.kt` | Test fixture with violations |

---

## Design Notes

- **Single engine instance**: `KtLintRuleEngine` created once at startup, reused forever
- **EditorConfig**: Auto-loads `.editorconfig` from project root if present
- **Thread-safe**: ktlint engine is safe for concurrent requests
- **Module isolation**: ktlint deps isolated in `server:ktlint` module

---

## Testing

### Kotlin Tests: `server/ktlint/src/test/kotlin/codelens/ktlint/KtlintProviderImplTest.kt`

Using JUnit5 + Kotlin Test (following `ClassGraphProviderImplTest.kt` pattern):

```kotlin
class KtlintProviderImplTest {
    private lateinit var provider: KtlintProviderImpl
    private lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        provider = KtlintProviderImpl()
        tempDir = Files.createTempDirectory("ktlint-test")
    }

    @Test
    fun `lintFile should detect style violations`()

    @Test
    fun `lintFile should return empty errors for compliant code`()

    @Test
    fun `formatFile should fix auto-correctable issues`()

    @Test
    fun `formatFile with writeToFile false should not modify file`()

    @Test
    fun `lintProject should scan all kotlin files`()

    @Test
    fun `lintProject should respect includeTests flag`()

    @Test
    fun `initialize should load editorconfig if present`()
}
```

### Python Tests: `cli/tests/test_lint.py`

Using pytest + monkeypatch (following `test_settings.py` pattern):

```python
class TestLintCheck:
    """Tests for codelens lint check command."""

    def test_lint_check_file_success(self, temp_project_dir: Path, monkeypatch) -> None:
        """Test linting a single file."""

    def test_lint_check_project_success(self, temp_project_dir: Path, monkeypatch) -> None:
        """Test linting entire project."""

    def test_lint_check_exits_with_error_when_violations_found(self, ...) -> None:
        """Test exit code 1 when lint errors exist."""


class TestLintFormat:
    """Tests for codelens lint format command."""

    def test_lint_format_file_success(self, temp_project_dir: Path, monkeypatch) -> None:
        """Test formatting a single file."""

    def test_lint_format_dry_run_does_not_modify(self, ...) -> None:
        """Test --dry-run flag."""

    def test_lint_format_project_success(self, ...) -> None:
        """Test formatting entire project."""
```

### Test Fixtures: `test-fixtures/sample-ratpack-app/src/main/kotlin/sample/`

Add Kotlin files with intentional style violations for testing:

```kotlin
// BadFormatting.kt - file with ktlint violations
fun badFunction( x:Int,y:Int ){  // spacing issues
    val z=x+y  // missing spaces
    if(z>0){   // missing space after if
        println( "result" )  // extra spaces in parens
    }
}
```

### New Test Files

| File | Purpose |
|------|---------|
| `server/ktlint/src/test/kotlin/.../KtlintProviderImplTest.kt` | Unit tests for provider |
| `cli/tests/test_lint.py` | CLI command tests |
| `test-fixtures/sample-ratpack-app/src/.../BadFormatting.kt` | Test fixture with violations |

---

## Documentation Updates

### docs/cli.md

Add new "Lint Commands" section after "Method Commands":

```markdown
## Lint Commands

Commands for linting and formatting Kotlin code are under `codelens lint`.

### codelens lint check

Check Kotlin files for style issues.

| Option | Description |
|--------|-------------|
| `FILE` | Optional file to check (checks project if omitted) |
| `--pattern` | Glob pattern to filter files |
| `--include-tests/--no-tests` | Include test files (default: true) |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

### codelens lint format

Format Kotlin files using ktlint.

| Option | Description |
|--------|-------------|
| `FILE` | Optional file to format (formats project if omitted) |
| `--pattern` | Glob pattern to filter files |
| `--include-tests/--no-tests` | Include test files (default: true) |
| `--dry-run`, `-n` | Preview changes without modifying files |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |
```

Also update the "CLI to API Mapping" table to add:
| `codelens lint check` | `POST /api/v1/ktlint/lint/project` | Check style issues |
| `codelens lint format` | `POST /api/v1/ktlint/format/project` | Format files |

---

### docs/api.md

Add new "Lint Endpoints" section after "Analysis Endpoints":

```markdown
## Lint Endpoints

These endpoints provide Kotlin linting and formatting via ktlint.

### POST /api/v1/ktlint/lint/file

Lint a single Kotlin file.

**Request Body:**
{ "filePath": "/path/to/file.kt" }

**Response:**
{
  "filePath": "/path/to/file.kt",
  "errors": [...],
  "errorCount": 3,
  "durationMs": 45
}

### POST /api/v1/ktlint/lint/project

Lint all Kotlin files in the project.

**Request Body:**
{ "pattern": "**/*.kt", "includeTests": true }

**Response:**
{
  "projectPath": "/path/to/project",
  "fileResults": [...],
  "filesScanned": 50,
  "filesWithErrors": 3,
  "totalErrorCount": 12,
  "durationMs": 250
}

### POST /api/v1/ktlint/format/file

Format a single Kotlin file.

**Request Body:**
{ "filePath": "/path/to/file.kt", "writeToFile": false }

### POST /api/v1/ktlint/format/project

Format all Kotlin files in the project.

**Request Body:**
{ "pattern": "**/*.kt", "includeTests": true, "dryRun": false }
```

---

### README.md

**Update "Quick Command Reference" section** - Add after "Annotation & Method Search":

```markdown
#### Linting & Formatting

```bash
codelens lint check                    # Check all Kotlin files for style issues
codelens lint check src/Main.kt        # Check a single file
codelens lint format                   # Format all Kotlin files
codelens lint format --dry-run         # Preview formatting changes
```

**Update "API Endpoints Quick Reference" table** - Add:

| `POST /api/v1/ktlint/lint/file` | Lint single file |
| `POST /api/v1/ktlint/lint/project` | Lint project |
| `POST /api/v1/ktlint/format/file` | Format single file |
| `POST /api/v1/ktlint/format/project` | Format project |

**Update "Repository Structure"** - Add `server/ktlint/` module:

```
│   ├── ktlint/                   # ktlint-based linting (warm server)
│   │   ├── build.gradle.kts
│   │   └── src/main/kotlin/
│   │       └── codelens/ktlint/
│   │           └── KtlintProvider.kt
```

**Update CLI commands structure** - Add:

```
│           ├── commands/
│           │   ├── lint.py           # lint check, lint format
```

---

## Files to Modify (Documentation)

| File | Changes |
|------|---------|
| `docs/cli.md` | Add "Lint Commands" section, update CLI-to-API mapping table |
| `docs/api.md` | Add "Lint Endpoints" section with 4 new endpoints |
| `README.md` | Add lint commands to quick reference, add endpoints to API table, update repo structure |
