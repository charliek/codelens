"""Project analysis commands."""

import asyncio
from typing import Optional

import typer
from rich.console import Console

from codelens_cli.container import ServiceContainer
from codelens_cli.errors import ExitCode
from codelens_cli.models import ProjectStatus
from codelens_cli.output import is_tty, print_json, print_project_info

console = Console()
err_console = Console(stderr=True)


def project_info(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
    once: bool = typer.Option(False, "--once", help="Start server, query, then stop"),
) -> None:
    """Show project information."""
    server_service = ServiceContainer.server_service()
    project_service = ServiceContainer.project_service()
    project_path = project_service.get_project_path(project)

    # Ensure server is running (auto-start)
    server = server_service.find_server(project_path)
    if server is None or server.status != ProjectStatus.READY:
        if not json_output and is_tty():
            err_console.print(
                f"Starting server for [cyan]{project_path.name}[/cyan]..."
            )

        try:
            server = asyncio.run(server_service.start_server(project_path))
        except Exception as e:
            err_console.print(f"[red]Error:[/red] {e}")
            raise typer.Exit(ExitCode.SERVER_ERROR)

    # Query project info
    try:
        project_data = project_service.get_project_info(project_path, server)

        if json_output or not is_tty():
            print_json(project_data.model_dump(by_alias=True))
        else:
            print_project_info(project_data.model_dump(by_alias=True), console)

    except Exception as e:
        err_console.print(f"[red]Error:[/red] {e}")
        raise typer.Exit(ExitCode.CONNECTION_ERROR)
    finally:
        if once:
            server_service.stop_server(project_path)
