"""State management for running CodeLens servers."""

import hashlib
import json
import os
import signal
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from codelens_cli.config import get_cache_dir


def project_hash(project_path: Path) -> str:
    """Generate a short hash for a project path."""
    canonical = str(project_path.resolve())
    return hashlib.sha256(canonical.encode()).hexdigest()[:12]


def get_state_dir() -> Path:
    """Get directory for server state files."""
    state_dir = get_cache_dir() / "servers"
    state_dir.mkdir(parents=True, exist_ok=True)
    return state_dir


def get_logs_dir() -> Path:
    """Get directory for server log files."""
    logs_dir = get_cache_dir() / "logs"
    logs_dir.mkdir(parents=True, exist_ok=True)
    return logs_dir


def get_state_file(project_path: Path) -> Path:
    """Get state file path for a project."""
    return get_state_dir() / f"{project_hash(project_path)}.json"


def get_log_file(project_path: Path) -> Path:
    """Get log file path for a project."""
    return get_logs_dir() / f"{project_hash(project_path)}.log"


def save_server_state(
    project_path: Path,
    pid: int,
    port: int,
    host: str,
    server_mode: str,
    idle_timeout: str,
) -> None:
    """Save server state to file."""
    state = {
        "pid": pid,
        "port": port,
        "host": host,
        "projectPath": str(project_path),
        "projectName": project_path.name,
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "lastActivityAt": datetime.now(timezone.utc).isoformat(),
        "idleTimeout": idle_timeout,
        "status": "STARTING",
        "serverMode": server_mode,
        "version": "0.1.0",
    }

    state_file = get_state_file(project_path)
    state_file.write_text(json.dumps(state, indent=2))


def update_server_status(project_path: Path, status: str) -> None:
    """Update server status in state file."""
    state_file = get_state_file(project_path)
    if state_file.exists():
        state = json.loads(state_file.read_text())
        state["status"] = status
        state["lastActivityAt"] = datetime.now(timezone.utc).isoformat()
        state_file.write_text(json.dumps(state, indent=2))


def load_server_state(project_path: Path) -> dict[str, Any] | None:
    """Load server state from file."""
    state_file = get_state_file(project_path)
    if not state_file.exists():
        return None

    try:
        return json.loads(state_file.read_text())
    except json.JSONDecodeError:
        return None


def delete_server_state(project_path: Path) -> None:
    """Delete server state file."""
    state_file = get_state_file(project_path)
    state_file.unlink(missing_ok=True)


def is_process_running(pid: int) -> bool:
    """Check if a process is running."""
    try:
        os.kill(pid, 0)
        return True
    except (OSError, ProcessLookupError):
        return False


def list_all_servers() -> list[dict[str, Any]]:
    """List all server state files, validating each."""
    state_dir = get_state_dir()
    if not state_dir.exists():
        return []

    servers = []
    for state_file in state_dir.glob("*.json"):
        try:
            state = json.loads(state_file.read_text())
            if is_process_running(state["pid"]):
                servers.append(state)
            else:
                # Clean up stale file
                state_file.unlink()
        except (json.JSONDecodeError, KeyError):
            state_file.unlink()

    return servers
