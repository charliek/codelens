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

// LintProjectResponse is the typed response from POST /api/v1/ktlint/lint/project.
type LintProjectResponse struct {
	ProjectPath     string             `json:"projectPath"`
	FileResults     []LintFileResponse `json:"fileResults"`
	FilesScanned    int                `json:"filesScanned"`
	FilesWithErrors int                `json:"filesWithErrors"`
	TotalErrorCount int                `json:"totalErrorCount"`
	DurationMs      int                `json:"durationMs"`
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
type FormatProjectResponse struct {
	ProjectPath      string               `json:"projectPath"`
	FileResults      []FormatFileResponse `json:"fileResults"`
	FilesScanned     int                  `json:"filesScanned"`
	FilesWithChanges int                  `json:"filesWithChanges"`
	DurationMs       int                  `json:"durationMs"`
}
