"""Characterization tests for ServerStateRepository.

These pin down the observable behavior of the server-state persistence
layer so that a future port (Go) can validate it produces byte-identical
state files and matches the stale-cleanup semantics.

What is locked here:

* Hash naming: state file = ``servers/{sha256(resolve(path))[:12]}.json``
* Log path:    ``logs/{sha256(resolve(path))[:12]}.log``
* JSON field aliases on disk (camelCase via ``by_alias=True``)
* Datetime ISO-8601 format with offset
* ``find`` deletes nothing (read-only); stale PID cleanup happens in
  the higher-level ``ServerService.find_server`` path, NOT here
* ``list_all`` deletes state files whose PIDs are no longer alive
* ``list_all`` deletes state files that fail to parse
"""

from __future__ import annotations

import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path

import pytest

from codelens_cli.models import ProjectStatus, ServerMode
from codelens_cli.repositories.server_state_repository import ServerStateRepository


def _expected_hash(project_path: Path) -> str:
    canonical = str(project_path.resolve())
    return hashlib.sha256(canonical.encode()).hexdigest()[:12]


@pytest.fixture
def repo(temp_cache_dir: Path) -> ServerStateRepository:
    return ServerStateRepository()


@pytest.fixture
def project_dir(tmp_path: Path) -> Path:
    project = tmp_path / "my-project"
    project.mkdir()
    (project / "build.gradle.kts").write_text("// test\n")
    return project


# -------------------- hash & path layout --------------------


def test_state_file_uses_sha256_first_12_chars_of_resolved_path(
    repo: ServerStateRepository, project_dir: Path, temp_cache_dir: Path
) -> None:
    state = repo.save(
        project_path=project_dir,
        pid=os.getpid(),
        port=8080,
        host="127.0.0.1",
        server_mode=ServerMode.GRADLE,
        idle_timeout="30m",
    )

    expected_name = f"{_expected_hash(project_dir)}.json"
    state_file = temp_cache_dir / "servers" / expected_name
    assert state_file.exists(), f"Expected state file at {state_file}"
    assert state.project_path == project_dir


def test_log_file_uses_same_hash_under_logs_dir(
    repo: ServerStateRepository, project_dir: Path, temp_cache_dir: Path
) -> None:
    log_path = repo.get_log_file(project_dir)
    expected = temp_cache_dir / "logs" / f"{_expected_hash(project_dir)}.log"
    assert log_path == expected


def test_save_creates_servers_and_logs_directories(temp_cache_dir: Path) -> None:
    # Instantiation alone should create both subdirs.
    ServerStateRepository()
    assert (temp_cache_dir / "servers").is_dir()
    assert (temp_cache_dir / "logs").is_dir()


# -------------------- JSON wire format --------------------


def test_state_json_uses_camelcase_aliases(
    repo: ServerStateRepository, project_dir: Path, temp_cache_dir: Path
) -> None:
    repo.save(
        project_path=project_dir,
        pid=12345,
        port=8080,
        host="127.0.0.1",
        server_mode=ServerMode.JAR,
        idle_timeout="30m",
    )
    raw = (temp_cache_dir / "servers" / f"{_expected_hash(project_dir)}.json").read_text()
    data = json.loads(raw)

    # Locked field names. A Go port must produce these exact keys.
    expected_keys = {
        "pid",
        "port",
        "host",
        "projectPath",
        "projectName",
        "startedAt",
        "lastActivityAt",
        "idleTimeout",
        "status",
        "serverMode",
        "version",
    }
    assert set(data.keys()) == expected_keys

    # Locked field values for the static ones.
    assert data["pid"] == 12345
    assert data["port"] == 8080
    assert data["host"] == "127.0.0.1"
    assert data["projectPath"].endswith("my-project")
    assert data["projectName"] == "my-project"
    assert data["serverMode"] == "jar"
    assert data["idleTimeout"] == "30m"
    assert data["status"] == ProjectStatus.STARTING.value


def test_state_json_datetimes_are_iso_with_offset(
    repo: ServerStateRepository, project_dir: Path, temp_cache_dir: Path
) -> None:
    repo.save(
        project_path=project_dir,
        pid=12345,
        port=8080,
        host="127.0.0.1",
        server_mode=ServerMode.GRADLE,
        idle_timeout="30m",
    )
    data = json.loads((temp_cache_dir / "servers" / f"{_expected_hash(project_dir)}.json").read_text())
    # ISO-8601 with offset (e.g. "2026-05-21 04:24:19.387465+00:00" -- pydantic's default).
    parsed = datetime.fromisoformat(data["startedAt"])
    assert parsed.tzinfo is not None
    # Saved values are UTC.
    assert parsed.utcoffset().total_seconds() == 0


def test_state_version_is_stamped_from_package_version(
    repo: ServerStateRepository, project_dir: Path, temp_cache_dir: Path
) -> None:
    from codelens_cli import __version__

    repo.save(
        project_path=project_dir,
        pid=12345,
        port=8080,
        host="127.0.0.1",
        server_mode=ServerMode.GRADLE,
        idle_timeout="30m",
    )
    data = json.loads((temp_cache_dir / "servers" / f"{_expected_hash(project_dir)}.json").read_text())
    assert data["version"] == __version__


# -------------------- find / update / delete --------------------


def test_find_returns_none_when_no_state_file(
    repo: ServerStateRepository, project_dir: Path
) -> None:
    assert repo.find(project_dir) is None


def test_find_round_trips_a_saved_state(
    repo: ServerStateRepository, project_dir: Path
) -> None:
    saved = repo.save(
        project_path=project_dir,
        pid=12345,
        port=8080,
        host="127.0.0.1",
        server_mode=ServerMode.GRADLE,
        idle_timeout="30m",
    )
    loaded = repo.find(project_dir)
    assert loaded is not None
    assert loaded.pid == 12345
    assert loaded.port == 8080
    assert loaded.server_mode == ServerMode.GRADLE
    assert loaded.idle_timeout == "30m"
    assert loaded.status == ProjectStatus.STARTING
    assert loaded.project_path == saved.project_path


def test_find_returns_none_on_corrupt_state_file(
    repo: ServerStateRepository, project_dir: Path, temp_cache_dir: Path
) -> None:
    state_file = temp_cache_dir / "servers" / f"{_expected_hash(project_dir)}.json"
    state_file.write_text("not json {{")
    assert repo.find(project_dir) is None


def test_update_status_persists_and_advances_last_activity(
    repo: ServerStateRepository, project_dir: Path
) -> None:
    saved = repo.save(
        project_path=project_dir,
        pid=os.getpid(),
        port=8080,
        host="127.0.0.1",
        server_mode=ServerMode.GRADLE,
        idle_timeout="30m",
    )
    before = saved.last_activity_at

    repo.update_status(project_dir, ProjectStatus.READY)
    after = repo.find(project_dir)

    assert after is not None
    assert after.status == ProjectStatus.READY
    assert after.last_activity_at >= before


def test_delete_removes_state_file_idempotently(
    repo: ServerStateRepository, project_dir: Path, temp_cache_dir: Path
) -> None:
    repo.save(
        project_path=project_dir,
        pid=os.getpid(),
        port=8080,
        host="127.0.0.1",
        server_mode=ServerMode.GRADLE,
        idle_timeout="30m",
    )
    repo.delete(project_dir)
    state_file = temp_cache_dir / "servers" / f"{_expected_hash(project_dir)}.json"
    assert not state_file.exists()
    # Idempotent.
    repo.delete(project_dir)


# -------------------- liveness / list_all cleanup --------------------


def test_is_process_running_for_current_process(repo: ServerStateRepository) -> None:
    assert repo.is_process_running(os.getpid()) is True


def test_is_process_running_for_obviously_dead_pid(repo: ServerStateRepository) -> None:
    # PID 0 reliably returns ESRCH on POSIX without a real process.
    # (We choose a very high PID to be safe across platforms.)
    assert repo.is_process_running(2**31 - 1) is False


def test_list_all_returns_alive_processes_only(
    repo: ServerStateRepository, tmp_path: Path
) -> None:
    alive_project = tmp_path / "alive"
    alive_project.mkdir()
    (alive_project / "build.gradle.kts").write_text("//\n")
    dead_project = tmp_path / "dead"
    dead_project.mkdir()
    (dead_project / "build.gradle.kts").write_text("//\n")

    repo.save(
        project_path=alive_project,
        pid=os.getpid(),
        port=8080,
        host="127.0.0.1",
        server_mode=ServerMode.GRADLE,
        idle_timeout="30m",
    )
    repo.save(
        project_path=dead_project,
        pid=2**31 - 1,  # bogus PID
        port=8081,
        host="127.0.0.1",
        server_mode=ServerMode.GRADLE,
        idle_timeout="30m",
    )

    servers = repo.list_all()
    pids = [s.pid for s in servers]
    assert os.getpid() in pids
    assert (2**31 - 1) not in pids


def test_list_all_cleans_up_dead_state_files(
    repo: ServerStateRepository, project_dir: Path, temp_cache_dir: Path
) -> None:
    repo.save(
        project_path=project_dir,
        pid=2**31 - 1,
        port=8080,
        host="127.0.0.1",
        server_mode=ServerMode.GRADLE,
        idle_timeout="30m",
    )
    state_file = temp_cache_dir / "servers" / f"{_expected_hash(project_dir)}.json"
    assert state_file.exists()

    repo.list_all()
    assert not state_file.exists(), "list_all should remove stale state files"


def test_list_all_cleans_up_corrupt_state_files(temp_cache_dir: Path) -> None:
    repo = ServerStateRepository()
    bogus = temp_cache_dir / "servers" / "deadbeef0000.json"
    bogus.write_text("not json")
    repo.list_all()
    assert not bogus.exists(), "list_all should remove unparseable state files"


def test_list_all_returns_empty_when_dir_missing(
    temp_cache_dir: Path,
) -> None:
    repo = ServerStateRepository()
    # Remove the servers dir entirely.
    servers_dir = temp_cache_dir / "servers"
    for f in servers_dir.iterdir():
        f.unlink()
    servers_dir.rmdir()
    assert repo.list_all() == []
