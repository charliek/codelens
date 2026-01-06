"""Pydantic models for CodeLens CLI."""

from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Optional

from pydantic import BaseModel, Field, field_validator


class ProjectStatus(str, Enum):
    """Project analysis status."""

    LOADING = "LOADING"
    READY = "READY"
    ERROR = "ERROR"
    STARTING = "STARTING"


class ServerMode(str, Enum):
    """Server execution mode."""

    AUTO = "auto"
    GRADLE = "gradle"
    JAR = "jar"


class ProjectInfo(BaseModel):
    """Project information from server."""

    name: str
    path: str
    status: ProjectStatus
    class_count: Optional[int] = Field(None, alias="classCount")
    handler_count: Optional[int] = Field(None, alias="handlerCount")
    scanned_at: Optional[str] = Field(None, alias="scannedAt")

    class Config:
        populate_by_name = True


class ServerInfo(BaseModel):
    """Server information."""

    version: str
    api_version: str = Field(alias="apiVersion")
    project_path: str = Field(alias="projectPath")
    project_name: str = Field(alias="projectName")
    port: int
    host: str
    status: str
    started_at: str = Field(alias="startedAt")
    uptime: str
    last_activity_at: str = Field(alias="lastActivityAt")
    idle_duration: str = Field(alias="idleDuration")
    idle_timeout: str = Field(alias="idleTimeout")

    class Config:
        populate_by_name = True


class HealthResponse(BaseModel):
    """Health check response."""

    status: str
    timestamp: str


class ReadyResponse(BaseModel):
    """Readiness check response."""

    ready: bool
    status: str
    project: str


class ServerState(BaseModel):
    """Persisted server state."""

    pid: int
    port: int
    host: str
    project_path: Path = Field(alias="projectPath")
    project_name: str = Field(alias="projectName")
    started_at: datetime = Field(alias="startedAt")
    last_activity_at: datetime = Field(alias="lastActivityAt")
    idle_timeout: str = Field(alias="idleTimeout")
    status: ProjectStatus
    server_mode: ServerMode = Field(alias="serverMode")
    version: str

    @field_validator("project_path", mode="before")
    @classmethod
    def parse_path(cls, v: str | Path) -> Path:
        """Parse path string to Path object."""
        return Path(v) if isinstance(v, str) else v

    @field_validator("started_at", "last_activity_at", mode="before")
    @classmethod
    def parse_datetime(cls, v: str | datetime) -> datetime:
        """Parse datetime string to datetime object."""
        if isinstance(v, str):
            return datetime.fromisoformat(v.replace("Z", "+00:00"))
        return v

    class Config:
        populate_by_name = True


class ServerStartRequest(BaseModel):
    """Request to start a server."""

    project_path: Path
    mode: Optional[ServerMode] = None
    port: Optional[int] = None
    timeout: int = 60


class ServerStopRequest(BaseModel):
    """Request to stop a server."""

    project_path: Path
    force: bool = False


class ProjectInfoRequest(BaseModel):
    """Request for project information."""

    project_path: Path
    once: bool = False


class ClassSource(str, Enum):
    """Source classification for a class."""

    PROJECT = "PROJECT"
    LIBRARY = "LIBRARY"
    JDK = "JDK"


class ClassSummary(BaseModel):
    """Summary information about a class."""

    fqn: str
    simple_name: str = Field(alias="simpleName")
    package_name: str = Field(alias="packageName")
    source: ClassSource
    is_interface: bool = Field(alias="isInterface")
    is_abstract: bool = Field(alias="isAbstract")
    is_enum: bool = Field(alias="isEnum")
    is_annotation: bool = Field(alias="isAnnotation")
    method_count: int = Field(alias="methodCount")
    field_count: int = Field(alias="fieldCount")

    class Config:
        populate_by_name = True


class ClassFilterSummary(BaseModel):
    """Summary of the filter that was applied."""

    package_pattern: Optional[str] = Field(None, alias="packagePattern")
    name_pattern: Optional[str] = Field(None, alias="namePattern")
    source: Optional[str] = None
    has_annotation: Optional[str] = Field(None, alias="hasAnnotation")
    extends_class: Optional[str] = Field(None, alias="extendsClass")
    implements_interface: Optional[str] = Field(None, alias="implementsInterface")

    class Config:
        populate_by_name = True


class ClassListResponse(BaseModel):
    """Response for class list endpoint."""

    classes: list[ClassSummary]
    total_count: int = Field(alias="totalCount")
    page: int
    page_size: int = Field(alias="pageSize")
    total_pages: int = Field(alias="totalPages")
    applied_filter: ClassFilterSummary = Field(alias="appliedFilter")

    class Config:
        populate_by_name = True


class AnnotationInfo(BaseModel):
    """Information about an annotation."""

    type: str
    parameters: dict[str, str] = {}


class ParameterInfo(BaseModel):
    """Information about a method parameter."""

    name: str
    type: str
    annotations: list[AnnotationInfo] = []


class MethodInfo(BaseModel):
    """Information about a method."""

    name: str
    visibility: str
    return_type: str = Field(alias="returnType")
    parameters: list[ParameterInfo] = []
    annotations: list[AnnotationInfo] = []
    is_static: bool = Field(False, alias="isStatic")
    is_abstract: bool = Field(False, alias="isAbstract")
    is_final: bool = Field(False, alias="isFinal")
    is_synthetic: bool = Field(False, alias="isSynthetic")

    class Config:
        populate_by_name = True


class FieldInfo(BaseModel):
    """Information about a field."""

    name: str
    visibility: str
    type: str
    annotations: list[AnnotationInfo] = []
    is_static: bool = Field(False, alias="isStatic")
    is_final: bool = Field(False, alias="isFinal")

    class Config:
        populate_by_name = True


class ClassName(BaseModel):
    """Class name components."""

    fqn: str
    simple_name: str = Field(alias="simpleName")
    package_name: str = Field(alias="packageName")

    class Config:
        populate_by_name = True


class ClassInfo(BaseModel):
    """Full detailed information about a class."""

    name: ClassName
    source: ClassSource
    visibility: str
    is_interface: bool = Field(False, alias="isInterface")
    is_abstract: bool = Field(False, alias="isAbstract")
    is_final: bool = Field(False, alias="isFinal")
    is_enum: bool = Field(False, alias="isEnum")
    is_annotation: bool = Field(False, alias="isAnnotation")
    is_synthetic: bool = Field(False, alias="isSynthetic")
    superclass: Optional[str] = None
    interfaces: list[str] = []
    annotations: list[AnnotationInfo] = []
    methods: list[MethodInfo] = []
    fields: list[FieldInfo] = []

    class Config:
        populate_by_name = True


class ClassDetailResponse(BaseModel):
    """Response for class details endpoint."""

    class_info: ClassInfo = Field(alias="classInfo")

    class Config:
        populate_by_name = True


class ScanStatistics(BaseModel):
    """Statistics about the scanned codebase."""

    project_class_count: int = Field(alias="projectClassCount")
    library_class_count: int = Field(alias="libraryClassCount")
    jdk_class_count: int = Field(alias="jdkClassCount")
    project_interface_count: int = Field(alias="projectInterfaceCount")
    project_abstract_class_count: int = Field(alias="projectAbstractClassCount")
    project_enum_count: int = Field(alias="projectEnumCount")
    project_annotation_count: int = Field(alias="projectAnnotationCount")
    project_method_count: int = Field(alias="projectMethodCount")
    project_field_count: int = Field(alias="projectFieldCount")
    classpath_resolved_by: str = Field(alias="classpathResolvedBy")
    classpath_entry_count: int = Field(alias="classpathEntryCount")
    scan_duration_ms: int = Field(alias="scanDurationMs")
    scanned_at: str = Field(alias="scannedAt")

    class Config:
        populate_by_name = True


class DependencyType(str, Enum):
    """Type of dependency relationship."""

    EXTENDS = "EXTENDS"
    IMPLEMENTS = "IMPLEMENTS"
    FIELD_TYPE = "FIELD_TYPE"
    METHOD_RETURN_TYPE = "METHOD_RETURN_TYPE"
    METHOD_PARAMETER = "METHOD_PARAMETER"
    TYPE_REFERENCE = "TYPE_REFERENCE"


class DependencyInfo(BaseModel):
    """Information about a single dependency."""

    class_fqn: str = Field(alias="classFqn")
    dependency_type: DependencyType = Field(alias="dependencyType")
    source: ClassSource
    location: Optional[str] = None

    class Config:
        populate_by_name = True


class DependenciesResponse(BaseModel):
    """Response for dependencies endpoint."""

    target_class: str = Field(alias="targetClass")
    outgoing: list[DependencyInfo]
    incoming: list[DependencyInfo]

    class Config:
        populate_by_name = True


class HierarchyNode(BaseModel):
    """Node in a class hierarchy tree."""

    class_fqn: str = Field(alias="classFqn")
    simple_name: str = Field(alias="simpleName")
    source: ClassSource
    is_interface: bool = Field(alias="isInterface")
    parent: Optional["HierarchyNode"] = None
    interfaces: list["HierarchyNode"] = []
    children: list["HierarchyNode"] = []

    class Config:
        populate_by_name = True


class HierarchyResponse(BaseModel):
    """Response for hierarchy endpoint."""

    target_class: str = Field(alias="targetClass")
    hierarchy: HierarchyNode

    class Config:
        populate_by_name = True


class ImplementationsResponse(BaseModel):
    """Response for implementations endpoint."""

    target_class: str = Field(alias="targetClass")
    direct_implementations: list[ClassSummary] = Field(alias="directImplementations")
    indirect_implementations: list[ClassSummary] = Field(alias="indirectImplementations")
    total_count: int = Field(alias="totalCount")

    class Config:
        populate_by_name = True


class MethodSearchResult(BaseModel):
    """Result of a method search."""

    class_fqn: str = Field(alias="classFqn")
    class_simple_name: str = Field(alias="classSimpleName")
    class_source: ClassSource = Field(alias="classSource")
    method: MethodInfo

    class Config:
        populate_by_name = True


class MethodSearchResponse(BaseModel):
    """Response for method search endpoint."""

    methods: list[MethodSearchResult]
    total_count: int = Field(alias="totalCount")
    page: int
    page_size: int = Field(alias="pageSize")
    total_pages: int = Field(alias="totalPages")

    class Config:
        populate_by_name = True


class AnnotationUsagesResponse(BaseModel):
    """Response for annotation usages endpoint."""

    annotation_fqn: str = Field(alias="annotationFqn")
    usages: list[ClassSummary]
    total_count: int = Field(alias="totalCount")

    class Config:
        populate_by_name = True


# ============================================================================
# Ktlint Models
# ============================================================================


class LintError(BaseModel):
    """A single lint error found in a file."""

    line: int
    col: int
    rule_id: str = Field(alias="ruleId")
    detail: str
    can_be_auto_corrected: bool = Field(alias="canBeAutoCorrected")

    class Config:
        populate_by_name = True


class FileLintResult(BaseModel):
    """Lint results for a single file."""

    file_path: str = Field(alias="filePath")
    errors: list[LintError]
    error_count: int = Field(alias="errorCount")

    class Config:
        populate_by_name = True


class LintFileResponse(BaseModel):
    """Response for linting a single file."""

    file_path: str = Field(alias="filePath")
    errors: list[LintError]
    error_count: int = Field(alias="errorCount")
    duration_ms: int = Field(alias="durationMs")

    class Config:
        populate_by_name = True


class LintProjectResponse(BaseModel):
    """Response for linting a project."""

    project_path: str = Field(alias="projectPath")
    file_results: list[FileLintResult] = Field(alias="fileResults")
    files_scanned: int = Field(alias="filesScanned")
    files_with_errors: int = Field(alias="filesWithErrors")
    total_error_count: int = Field(alias="totalErrorCount")
    duration_ms: int = Field(alias="durationMs")

    class Config:
        populate_by_name = True


class FormatFileResponse(BaseModel):
    """Response for formatting a single file."""

    file_path: str = Field(alias="filePath")
    formatted_content: Optional[str] = Field(None, alias="formattedContent")
    has_changes: bool = Field(alias="hasChanges")
    remaining_errors: list[LintError] = Field(alias="remainingErrors")
    duration_ms: int = Field(alias="durationMs")

    class Config:
        populate_by_name = True


class FormatProjectResponse(BaseModel):
    """Response for formatting a project."""

    project_path: str = Field(alias="projectPath")
    files_formatted: list[str] = Field(alias="filesFormatted")
    files_scanned: int = Field(alias="filesScanned")
    files_with_changes: int = Field(alias="filesWithChanges")
    duration_ms: int = Field(alias="durationMs")

    class Config:
        populate_by_name = True
