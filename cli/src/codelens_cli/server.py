"""Server process management."""

import asyncio
import os
import re
import signal
import subprocess
import sys
from pathlib import Path

from rich.console import Console

from codelens_cli.config import find_repo_path, load_config
from codelens_cli.state import (
    delete_server_state,
    get_log_file,
    is_process_running,
    load_server_state,
    save_server_state,
    update_server_status,
)

console = Console(stderr=True)


def find_server(project_path: Path) -> dict | None:
    """Find running server for a project."""
    state = load_server_state(project_path)
    if state is None:
        return None

    if not is_process_running(state["pid"]):
        delete_server_state(project_path)
        return None

    return state


def determine_server_mode(config: dict) -> str:
    """Determine whether to use gradle or jar mode."""
    mode = config["server"]["mode"]
    if mode in ("gradle", "jar"):
        return mode

    # Auto mode: check if JAR exists
    try:
        repo_path = find_repo_path()
        jar_path = repo_path / "server" / "app" / "build" / "libs" / "codelens-server-all.jar"
        if jar_path.exists():
            return "jar"
    except RuntimeError:
        pass

    return "gradle"


def allocate_port(config: dict) -> int:
    """Find an available port."""
    import socket

    start = config["server"]["port_range"]["start"]
    end = config["server"]["port_range"]["end"]

    for port in range(start, end + 1):
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
                s.bind(("127.0.0.1", port))
                return port
        except OSError:
            continue

    raise RuntimeError(f"No available ports in range {start}-{end}")


async def start_server(
    project_path: Path,
    mode: str | None = None,
    port: int | None = None,
    timeout: int = 60,
) -> dict:
    """Start the CodeLens server for a project."""
    config = load_config()

    # Check if already running
    existing = find_server(project_path)
    if existing and existing.get("status") == "READY":
        return existing

    # Determine mode and port
    mode = mode or determine_server_mode(config)
    port = port or allocate_port(config)
    idle_timeout = config["server"]["idle_timeout"]
    host = config["server"]["host"]

    repo_path = find_repo_path()
    log_file = get_log_file(project_path)

    # Build command
    if mode == "gradle":
        cmd = [
            str(repo_path / "gradlew"),
            ":server:app:run",
            f"--args=--project {project_path} --port {port} --idle-timeout {idle_timeout}",
        ]
        cwd = repo_path
    else:
        jar_path = repo_path / "server" / "app" / "build" / "libs" / "codelens-server-all.jar"
        if not jar_path.exists():
            console.print(f"[red]Error:[/red] Server JAR not found at {jar_path}")
            console.print("\nBuild it with: [cyan]./gradlew :server:app:shadowJar[/cyan]")
            console.print("Or use: [cyan]codelens start --mode gradle[/cyan]")
            raise SystemExit(4)

        java_home = config["java"]["home"] or os.environ.get("JAVA_HOME")
        if java_home:
            java_bin = Path(java_home) / "bin" / "java"
            java_cmd = str(java_bin) if java_bin.exists() else "java"
        else:
            java_cmd = "java"

        cmd = [
            java_cmd,
            *config["java"]["opts"],
            "-jar", str(jar_path),
            "--project", str(project_path),
            "--port", str(port),
            "--idle-timeout", idle_timeout,
        ]
        cwd = None

    # Start process
    with open(log_file, "w") as log:
        process = subprocess.Popen(
            cmd,
            cwd=cwd,
            stdout=subprocess.PIPE,
            stderr=log,
            start_new_session=True,
            text=True,
        )

    # Save initial state
    save_server_state(project_path, process.pid, port, host, mode, idle_timeout)

    # Wait for ready signal
    try:
        ready_info = await wait_for_ready(process, timeout)
        update_server_status(project_path, "READY")

        state = load_server_state(project_path)
        state["port"] = ready_info["port"]  # Use actual port from server
        return state

    except TimeoutError:
        process.terminate()
        delete_server_state(project_path)
        raise


async def wait_for_ready(process: subprocess.Popen, timeout: int) -> dict:
    """Wait for server to print CODELENS_READY."""
    ready_pattern = re.compile(r"CODELENS_READY port=(\d+) host=(\S+) version=(\S+)")

    loop = asyncio.get_event_loop()
    start_time = loop.time()

    while loop.time() - start_time < timeout:
        # Check if process died
        if process.poll() is not None:
            raise RuntimeError(f"Server process exited with code {process.returncode}")

        # Try to read a line (non-blocking would be better, but this works)
        try:
            line = process.stdout.readline()
            if line:
                match = ready_pattern.search(line)
                if match:
                    return {
                        "port": int(match.group(1)),
                        "host": match.group(2),
                        "version": match.group(3),
                    }
        except Exception:
            pass

        await asyncio.sleep(0.1)

    raise TimeoutError(f"Server did not become ready within {timeout}s")


def stop_server(project_path: Path, force: bool = False) -> bool:
    """Stop the server for a project."""
    state = find_server(project_path)
    if state is None:
        return False

    pid = state["pid"]

    # Try graceful shutdown via API first
    if not force:
        try:
            import httpx
            response = httpx.post(
                f"http://{state['host']}:{state['port']}/admin/shutdown",
                timeout=5,
            )
            if response.status_code == 200:
                # Wait for process to exit
                for _ in range(50):  # 5 seconds
                    if not is_process_running(pid):
                        break
                    import time
                    time.sleep(0.1)
        except Exception:
            pass

    # Force kill if still running
    if is_process_running(pid):
        try:
            os.kill(pid, signal.SIGTERM)
            import time
            time.sleep(0.5)
            if is_process_running(pid):
                os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass

    delete_server_state(project_path)
    return True
