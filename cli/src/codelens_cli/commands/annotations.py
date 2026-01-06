"""Annotation analysis commands."""

import asyncio
from typing import Optional

import typer
from rich.console import Console
from rich.table import Table

from codelens_cli.client import CodeLensClient
from codelens_cli.container import ServiceContainer
from codelens_cli.errors import ExitCode
from codelens_cli.models import (
    AnnotationUsagesResponse,
    ClassSummary,
    ProjectStatus,
)
from codelens_cli.output import is_tty, print_json

app = typer.Typer(
    name="annotations",
    help="Find and analyze annotation usages in the codebase.",
    no_args_is_help=True,
)

console = Console()
err_console = Console(stderr=True)


def _ensure_server_running(project: Optional[str], json_output: bool):
    """Ensure a server is running for the project."""
    server_service = ServiceContainer.server_service()
    project_service = ServiceContainer.project_service()
    project_path = project_service.get_project_path(project)

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

    return server, project_path


def _get_client(server) -> CodeLensClient:
    """Create a client for the server."""
    return CodeLensClient(server.host, server.port)


@app.command(name="usages")
def show_usages(
    fqn: str = typer.Argument(help="Fully qualified annotation name"),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    include_libraries: bool = typer.Option(
        False, "--include-libraries", "-L", help="Include library classes"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Find all classes using a specific annotation."""
    server, project_path = _ensure_server_running(project, json_output)
    client = _get_client(server)

    try:
        result = client.get_annotation_usages(fqn, include_libraries)

        if json_output or not is_tty():
            print_json(result)
        else:
            response = AnnotationUsagesResponse.model_validate(result)
            _print_annotation_usages(response)

    except Exception as e:
        err_console.print(f"[red]Error:[/red] {e}")
        raise typer.Exit(ExitCode.CONNECTION_ERROR)


def _get_type_str(cls: ClassSummary) -> str:
    """Get type string for a class."""
    if cls.is_interface:
        return "[blue]interface[/]"
    if cls.is_annotation:
        return "[magenta]annotation[/]"
    if cls.is_enum:
        return "[yellow]enum[/]"
    if cls.is_abstract:
        return "[dim]abstract[/]"
    return "class"


def _print_annotation_usages(response: AnnotationUsagesResponse) -> None:
    """Print annotation usages in a nice table format."""
    console.print(f"\n[bold]Usages of @{response.annotation_fqn.split('.')[-1]}[/bold]")
    console.print(f"Total: {response.total_count} classes")
    console.print()

    if response.total_count == 0:
        console.print("[yellow]No classes found using this annotation.[/yellow]")
        return

    table = Table(show_header=True, header_style="bold")
    table.add_column("Class", style="cyan")
    table.add_column("Type", justify="center")
    table.add_column("Package")
    table.add_column("Source", justify="center")

    for cls in response.usages:
        type_str = _get_type_str(cls)
        source_color = {"PROJECT": "green", "LIBRARY": "yellow", "JDK": "dim"}.get(
            cls.source.value, ""
        )
        table.add_row(
            cls.simple_name,
            type_str,
            cls.package_name,
            f"[{source_color}]{cls.source.value}[/]",
        )

    console.print(table)
    console.print()
