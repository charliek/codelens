package client

// LintError mirrors the Kotlin LintError JSON shape.
type LintError struct {
	Line               int    `json:"line"`
	Col                int    `json:"col"`
	RuleID             string `json:"ruleId"`
	Detail             string `json:"detail"`
	CanBeAutoCorrected bool   `json:"canBeAutoCorrected"`
}

// LintFileResponse is the typed response from POST /api/v1/ktlint/lint/file.
type LintFileResponse struct {
	FilePath   string      `json:"filePath"`
	Errors     []LintError `json:"errors"`
	ErrorCount int         `json:"errorCount"`
	DurationMs int         `json:"durationMs"`
}

// FileLintResult is one entry in LintProjectResponse.FileResults. Mirrors the
// Kotlin FileLintResult (server/.../KtlintModels.kt:26). Notably has NO
// durationMs field — duration is reported once per project, not per file.
// Reusing LintFileResponse here would add a spurious "durationMs": 0 on
// re-serialization and break parity with the Python CLI.
type FileLintResult struct {
	FilePath   string      `json:"filePath"`
	Errors     []LintError `json:"errors"`
	ErrorCount int         `json:"errorCount"`
}

// LintProjectResponse is the typed response from POST /api/v1/ktlint/lint/project.
type LintProjectResponse struct {
	ProjectPath     string           `json:"projectPath"`
	FileResults     []FileLintResult `json:"fileResults"`
	FilesScanned    int              `json:"filesScanned"`
	FilesWithErrors int              `json:"filesWithErrors"`
	TotalErrorCount int              `json:"totalErrorCount"`
	DurationMs      int              `json:"durationMs"`
}

// FormatFileResponse is the typed response from POST /api/v1/ktlint/format/file.
type FormatFileResponse struct {
	FilePath         string      `json:"filePath"`
	FormattedContent *string     `json:"formattedContent"`
	HasChanges       bool        `json:"hasChanges"`
	RemainingErrors  []LintError `json:"remainingErrors"`
	DurationMs       int         `json:"durationMs"`
}

// FormatProjectResponse is the typed response from POST /api/v1/ktlint/format/project.
// The server emits filesFormatted: List<String> (server/.../KtlintModels.kt:90),
// NOT a per-file result list. Mirror that exactly so the formatted file
// names survive the JSON round-trip.
type FormatProjectResponse struct {
	ProjectPath      string   `json:"projectPath"`
	FilesFormatted   []string `json:"filesFormatted"`
	FilesScanned     int      `json:"filesScanned"`
	FilesWithChanges int      `json:"filesWithChanges"`
	DurationMs       int      `json:"durationMs"`
}
