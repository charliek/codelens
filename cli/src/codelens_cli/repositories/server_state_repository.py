"""Repository for server state persistence."""

import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from codelens_cli.models import ServerMode, ServerState, ProjectStatus
from codelens_cli.settings import get_cache_dir


class ServerStateRepository:
    """Repository for managing server state persistence."""

    def __init__(self) -> None:
        """Initialize repository."""
        self._state_dir = get_cache_dir() / "servers"
        self._logs_dir = get_cache_dir() / "logs"
        self._state_dir.mkdir(parents=True, exist_ok=True)
        self._logs_dir.mkdir(parents=True, exist_ok=True)

    def _project_hash(self, project_path: Path) -> str:
        """Generate a short hash for a project path."""
        canonical = str(project_path.resolve())
        return hashlib.sha256(canonical.encode()).hexdigest()[:12]

    def _get_state_file(self, project_path: Path) -> Path:
        """Get state file path for a project."""
        return self._state_dir / f"{self._project_hash(project_path)}.json"

    def get_log_file(self, project_path: Path) -> Path:
        """Get log file path for a project."""
        return self._logs_dir / f"{self._project_hash(project_path)}.log"

    def save(
        self,
        project_path: Path,
        pid: int,
        port: int,
        host: str,
        server_mode: ServerMode,
        idle_timeout: str,
    ) -> ServerState:
        """Save server state to file."""
        now = datetime.now(timezone.utc)
        state = ServerState(
            pid=pid,
            port=port,
            host=host,
            projectPath=project_path,
            projectName=project_path.name,
            startedAt=now,
            lastActivityAt=now,
            idleTimeout=idle_timeout,
            status=ProjectStatus.STARTING,
            serverMode=server_mode,
            version="0.1.0",
        )

        state_file = self._get_state_file(project_path)
        state_file.write_text(state.model_dump_json(by_alias=True, indent=2))
        return state

    def find(self, project_path: Path) -> Optional[ServerState]:
        """Load server state from file."""
        state_file = self._get_state_file(project_path)
        if not state_file.exists():
            return None

        try:
            data = json.loads(state_file.read_text())
            return ServerState.model_validate(data)
        except (json.JSONDecodeError, ValueError):
            return None

    def update_status(self, project_path: Path, status: ProjectStatus) -> None:
        """Update server status in state file."""
        state = self.find(project_path)
        if state:
            state.status = status
            state.last_activity_at = datetime.now(timezone.utc)
            state_file = self._get_state_file(project_path)
            state_file.write_text(state.model_dump_json(by_alias=True, indent=2))

    def delete(self, project_path: Path) -> None:
        """Delete server state file."""
        state_file = self._get_state_file(project_path)
        state_file.unlink(missing_ok=True)

    def is_process_running(self, pid: int) -> bool:
        """Check if a process is running."""
        try:
            os.kill(pid, 0)
            return True
        except (OSError, ProcessLookupError):
            return False

    def list_all(self) -> list[ServerState]:
        """List all server state files, validating each."""
        if not self._state_dir.exists():
            return []

        servers = []
        for state_file in self._state_dir.glob("*.json"):
            try:
                data = json.loads(state_file.read_text())
                state = ServerState.model_validate(data)
                if self.is_process_running(state.pid):
                    servers.append(state)
                else:
                    # Clean up stale file
                    state_file.unlink()
            except (json.JSONDecodeError, ValueError, KeyError):
                state_file.unlink()

        return servers
