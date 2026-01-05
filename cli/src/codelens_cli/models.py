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
