"""Service for server lifecycle management."""

import asyncio
import logging
import os
import re
import signal
import socket
import subprocess
import time
from pathlib import Path
from typing import Optional

import httpx

from codelens_cli.models import ProjectStatus, ServerMode, ServerState
from codelens_cli.repositories import ServerStateRepository
from codelens_cli.settings import (
    AppSettings,
    detect_project_java_version,
    find_repo_path,
    get_codelens_java_version,
    get_gradle_version,
    needs_older_java_for_gradle,
    resolve_codelens_java_home,
    resolve_project_java_home,
)

logger = logging.getLogger(__name__)


class ServerService:
    """Service for managing server lifecycle."""

    def __init__(
        self,
        repository: Optional[ServerStateRepository] = None,
        settings: Optional[AppSettings] = None,
    ) -> None:
        """Initialize service."""
        self.repository = repository or ServerStateRepository()
        self.settings = settings or AppSettings()

    def find_server(self, project_path: Path) -> Optional[ServerState]:
        """Find running server for a project."""
        state = self.repository.find(project_path)
        if state is None:
            return None

        if not self.repository.is_process_running(state.pid):
            self.repository.delete(project_path)
            return None

        return state

    def determine_server_mode(self, mode: Optional[ServerMode] = None) -> ServerMode:
        """Determine whether to use gradle or jar mode."""
        if mode:
            return mode

        mode = self.settings.server.mode
        if mode in (ServerMode.GRADLE, ServerMode.JAR):
            return mode

        # Auto mode: check if JAR exists
        try:
            repo_path = find_repo_path()
            jar_path = (
                repo_path / "server" / "app" / "build" / "libs" / "codelens-server-all.jar"
            )
            if jar_path.exists():
                return ServerMode.JAR
        except RuntimeError:
            pass

        return ServerMode.GRADLE

    def allocate_port(self) -> int:
        """Find an available port."""
        start = self.settings.server.port_range.start
        end = self.settings.server.port_range.end

        for port in range(start, end + 1):
            try:
                with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
                    s.bind(("127.0.0.1", port))
                    return port
            except OSError:
                continue

        raise RuntimeError(f"No available ports in range {start}-{end}")

    async def start_server(
        self,
        project_path: Path,
        mode: Optional[ServerMode] = None,
        port: Optional[int] = None,
        timeout: int = 60,
        project_java_home: Optional[Path] = None,
    ) -> ServerState:
        """Start the CodeLens server for a project.

        Args:
            project_path: Path to the target project
            mode: Server mode (gradle or jar)
            port: Port to use (auto-allocated if not specified)
            timeout: Startup timeout in seconds
            project_java_home: Java home for target project's Gradle (auto-detected if not specified)
        """
        # Check if already running
        existing = self.find_server(project_path)
        if existing and existing.status == ProjectStatus.READY:
            return existing

        # Determine mode and port
        server_mode = self.determine_server_mode(mode)
        server_port = port or self.allocate_port()
        idle_timeout = self.settings.server.idle_timeout
        host = self.settings.server.host

        repo_path = find_repo_path()
        log_file = self.repository.get_log_file(project_path)

        # Determine project Java home for Gradle operations (for older Gradle versions)
        effective_project_java: Optional[Path] = None
        project_java_source: Optional[str] = None

        if project_java_home:
            # 1. Explicit override takes priority
            effective_project_java = project_java_home
            project_java_source = "explicit --project-java argument"
        elif needs_older_java_for_gradle(project_path):
            # 2. Auto-detect if project needs older Java
            detected = resolve_project_java_home(project_path)
            if detected:
                effective_project_java = detected
                project_java_version = detect_project_java_version(project_path)
                project_java_source = f"auto-detected from project ({project_java_version})"
            else:
                # Warn user that they may need to provide Java home
                project_java_version = detect_project_java_version(project_path)
                gradle_version = get_gradle_version(project_path)
                if project_java_version:
                    logger.warning(
                        "Project uses Gradle %s which requires an older Java. "
                        "Detected version %s but could not find it in SDKMAN. "
                        "Install with: sdk install java %s",
                        gradle_version,
                        project_java_version,
                        project_java_version,
                    )
                elif gradle_version:
                    logger.warning(
                        "Project uses Gradle %s which may require an older Java. "
                        "If startup fails, specify --project-java or add a .sdkmanrc file.",
                        gradle_version,
                    )

        if effective_project_java:
            logger.info(
                "Using %s for Gradle operations: %s",
                project_java_source,
                effective_project_java,
            )

        # Build command
        if server_mode == ServerMode.GRADLE:
            cmd = [
                str(repo_path / "gradlew"),
                ":server:app:run",
                f"--args=--project {project_path} --port {server_port} --idle-timeout {idle_timeout}",
            ]
            cwd: Optional[Path] = repo_path
        else:
            jar_path = (
                repo_path / "server" / "app" / "build" / "libs" / "codelens-server-all.jar"
            )
            if not jar_path.exists():
                raise FileNotFoundError(
                    f"Server JAR not found at {jar_path}\n"
                    f"Build it with: ./gradlew :server:app:shadowJar\n"
                    f"Or use: codelens start --mode gradle"
                )

            # Priority order for Java resolution:
            # 1. Explicit setting (CODELENS_JAVA_HOME env var or config)
            # 2. SDKMAN detection (read codelens .sdkmanrc, find in ~/.sdkman)
            # 3. JAVA_HOME environment variable
            # 4. System PATH
            java_home: Optional[Path] = None
            java_source: Optional[str] = None

            # 1. Check explicit setting
            if self.settings.java.home:
                java_home = Path(self.settings.java.home)
                java_source = "CODELENS_JAVA_HOME setting"

            # 2. Try SDKMAN detection
            if java_home is None:
                sdkman_java = resolve_codelens_java_home()
                if sdkman_java:
                    java_home = sdkman_java
                    java_source = f"SDKMAN ({sdkman_java.name})"

            # 3. Fall back to JAVA_HOME
            if java_home is None:
                env_java_home = os.environ.get("JAVA_HOME")
                if env_java_home:
                    java_home = Path(env_java_home)
                    java_source = "JAVA_HOME environment variable"

            # Resolve java binary
            if java_home and (java_home / "bin" / "java").exists():
                java_cmd = str(java_home / "bin" / "java")
                logger.debug("Using Java from %s: %s", java_source, java_cmd)
            else:
                # 4. Fall back to PATH
                java_cmd = "java"
                java_source = "system PATH"
                logger.debug("Using Java from %s", java_source)

                # Warn if we couldn't find the required version
                required_version = get_codelens_java_version()
                if required_version:
                    logger.warning(
                        "Could not find Java %s in SDKMAN. "
                        "Install with: sdk install java %s",
                        required_version,
                        required_version,
                    )

            cmd = [
                java_cmd,
                *self.settings.java.opts,
                "-jar",
                str(jar_path),
                "--project",
                str(project_path),
                "--port",
                str(server_port),
                "--idle-timeout",
                idle_timeout,
            ]

            # Add project Java home if detected/specified (for older Gradle versions)
            if effective_project_java:
                cmd.extend(["--project-java-home", str(effective_project_java)])

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
        state = self.repository.save(
            project_path, process.pid, server_port, host, server_mode, idle_timeout
        )

        # Wait for ready signal
        try:
            ready_info = await self._wait_for_ready(process, timeout, log_file, project_path)
            self.repository.update_status(project_path, ProjectStatus.READY)

            # Reload state with updated port from server
            state = self.repository.find(project_path)
            if state:
                state.port = ready_info["port"]
            return state or state  # Return original if reload fails

        except (TimeoutError, RuntimeError):
            process.terminate()
            self.repository.delete(project_path)
            raise

    async def _wait_for_ready(
        self, process: subprocess.Popen, timeout: int, log_file: Path, project_path: Optional[Path] = None
    ) -> dict[str, int | str]:
        """Wait for server to print CODELENS_READY."""
        ready_pattern = re.compile(r"CODELENS_READY port=(\d+) host=(\S+) version=(\S+)")

        loop = asyncio.get_event_loop()
        start_time = loop.time()

        while loop.time() - start_time < timeout:
            # Check if process died
            if process.poll() is not None:
                self._check_for_java_version_error(log_file, project_path)
                raise RuntimeError(
                    f"Server process exited with code {process.returncode}"
                )

            # Try to read a line
            try:
                if process.stdout:
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

    def _check_for_java_version_error(self, log_file: Path, project_path: Optional[Path] = None) -> None:
        """Check if the server failed due to Java version mismatch.

        Reads the log file to detect Java version errors and raises
        a helpful error message with solutions.
        """
        try:
            if not log_file.exists():
                return

            log_content = log_file.read_text()

            # Check for Gradle Tooling API Java version error
            if "Unsupported class file major version" in log_content:
                gradle_version = get_gradle_version(project_path) if project_path else None
                project_java = detect_project_java_version(project_path) if project_path else None

                error_msg = "Gradle version incompatibility detected.\n\n"
                if gradle_version:
                    error_msg += f"The target project uses Gradle {gradle_version}, which cannot run with Java 21.\n"
                else:
                    error_msg += "The target project's Gradle version cannot run with Java 21.\n"

                if project_java:
                    error_msg += (
                        f"\nThe project specifies Java {project_java}.\n\n"
                        f"Solutions:\n"
                        f"  1. Install the required Java: sdk install java {project_java}\n"
                        f"  2. Retry - CodeLens will auto-detect and use the project's Java\n"
                        f"  3. Or specify explicitly: codelens start --project-java ~/.sdkman/candidates/java/{project_java}\n"
                    )
                else:
                    error_msg += (
                        "\nSolutions:\n"
                        "  1. Add a .sdkmanrc file to the project with: java=11.0.28-tem (or appropriate version)\n"
                        "  2. Specify Java explicitly: codelens start --project-java /path/to/java/home\n"
                        "  3. Use classpath file mode: codelens start --classpath-file <path>\n"
                    )

                raise RuntimeError(error_msg)

            # Check for server JAR Java version error
            if "UnsupportedClassVersionError" in log_content:
                required_version = get_codelens_java_version() or "21"
                raise RuntimeError(
                    f"Java version mismatch: The codelens server requires Java {required_version}.\n"
                    f"Your current Java is too old to run the compiled server JAR.\n\n"
                    f"Solutions:\n"
                    f"  1. Install the required Java: sdk install java {required_version}\n"
                    f"  2. Set CODELENS_JAVA_HOME to point to a Java 21+ installation\n"
                    f"  3. Use gradle mode instead: codelens start --mode gradle"
                )
        except RuntimeError:
            raise
        except Exception:
            pass  # Ignore read errors

    def stop_server(self, project_path: Path, force: bool = False) -> bool:
        """Stop the server for a project."""
        state = self.find_server(project_path)
        if state is None:
            return False

        pid = state.pid

        # Try graceful shutdown via API first
        if not force:
            try:
                response = httpx.post(
                    f"http://{state.host}:{state.port}/admin/shutdown",
                    timeout=5,
                )
                if response.status_code == 200:
                    # Wait for process to exit
                    for _ in range(50):  # 5 seconds
                        if not self.repository.is_process_running(pid):
                            break
                        time.sleep(0.1)
            except Exception as e:
                logger.debug("Graceful shutdown failed: %s", e)

        # Force kill if still running
        if self.repository.is_process_running(pid):
            try:
                os.kill(pid, signal.SIGTERM)
                time.sleep(0.5)
                if self.repository.is_process_running(pid):
                    os.kill(pid, signal.SIGKILL)
            except ProcessLookupError:
                logger.debug("Process %d already terminated", pid)

        self.repository.delete(project_path)
        return True

    def list_servers(self) -> list[ServerState]:
        """List all running servers."""
        return self.repository.list_all()
