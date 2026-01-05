"""Service for project operations."""

from pathlib import Path
from typing import Optional

import typer
from rich.console import Console

from codelens_cli.client import CodeLensClient
from codelens_cli.models import ProjectInfo, ServerState
from codelens_cli.services.server_service import ServerService


class ProjectService:
    """Service for project operations."""

    def __init__(
        self,
        server_service: Optional[ServerService] = None,
        console: Optional[Console] = None,
    ) -> None:
        """Initialize service."""
        self.server_service = server_service or ServerService()
        self.console = console or Console(stderr=True)

    def get_project_path(self, project: Optional[str]) -> Path:
        """Get project path from argument or current directory."""
        if project:
            path = Path(project).resolve()
        else:
            path = Path.cwd()

        # Validate it's a Gradle project
        if not path.exists():
            raise typer.Exit(code=3)

        has_build_file = (path / "build.gradle").exists() or (
            path / "build.gradle.kts"
        ).exists()
        if not has_build_file:
            self.console.print(
                f"[red]Error:[/red] No build.gradle or build.gradle.kts found in {path}"
            )
            self.console.print("\nCodeLens requires a Gradle project directory.")
            self.console.print(f"\nTry: [cyan]cd /path/to/your/project[/cyan]")
            raise typer.Exit(code=3)

        return path

    def get_project_info(self, project_path: Path, server: ServerState) -> ProjectInfo:
        """Get project information from server."""
        client = CodeLensClient(server.host, server.port)
        data = client.project()
        return ProjectInfo.model_validate(data)

    def refresh_project(self, project_path: Path, server: ServerState) -> ProjectInfo:
        """Refresh project scan."""
        client = CodeLensClient(server.host, server.port)
        data = client.refresh()
        return ProjectInfo.model_validate(data)
