"""Method search commands."""

import asyncio
from typing import Optional

import typer
from rich.console import Console
from rich.table import Table

from codelens_cli.client import CodeLensClient
from codelens_cli.container import ServiceContainer
from codelens_cli.errors import ExitCode
from codelens_cli.models import (
    MethodSearchResponse,
    ProjectStatus,
)
from codelens_cli.output import is_tty, print_json

app = typer.Typer(
    name="methods",
    help="Search and analyze methods in the codebase.",
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


@app.command(name="search")
def search_methods(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    name: Optional[str] = typer.Option(
        None, "--name", "-n", help="Filter by method name pattern (supports * wildcard)"
    ),
    return_type: Optional[str] = typer.Option(
        None, "--return-type", "-r", help="Filter by return type FQN"
    ),
    annotation: Optional[str] = typer.Option(
        None, "--annotation", "-a", help="Filter to methods with this annotation"
    ),
    in_class: Optional[str] = typer.Option(
        None, "--class", "-c", help="Filter by containing class FQN"
    ),
    in_package: Optional[str] = typer.Option(
        None, "--package", help="Filter by containing package pattern"
    ),
    include_libraries: bool = typer.Option(
        False, "--include-libraries", "-L", help="Include library classes"
    ),
    page: int = typer.Option(0, "--page", help="Page number (0-based)"),
    size: int = typer.Option(50, "--size", help="Page size"),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Search methods across all classes."""
    server, project_path = _ensure_server_running(project, json_output)
    client = _get_client(server)

    try:
        result = client.search_methods(
            name=name,
            return_type=return_type,
            annotation=annotation,
            in_class=in_class,
            in_package=in_package,
            include_libraries=include_libraries,
            page=page,
            size=size,
        )

        if json_output or not is_tty():
            print_json(result)
        else:
            response = MethodSearchResponse.model_validate(result)
            _print_method_search(response)

    except Exception as e:
        err_console.print(f"[red]Error:[/red] {e}")
        raise typer.Exit(ExitCode.CONNECTION_ERROR)


def _print_method_search(response: MethodSearchResponse) -> None:
    """Print method search results in a nice table format."""
    if response.total_count == 0:
        console.print("[yellow]No methods found matching the filter.[/yellow]")
        return

    start = response.page * response.page_size + 1
    end = start + len(response.methods) - 1
    console.print(f"\n[bold]Methods[/bold] ({start}-{end} of {response.total_count})")
    console.print()

    table = Table(show_header=True, header_style="bold")
    table.add_column("Method", style="cyan")
    table.add_column("Return Type")
    table.add_column("Class")
    table.add_column("Source", justify="center")

    for result in response.methods:
        method = result.method
        # Build method signature
        params = ", ".join(
            [f"{p.type.split('.')[-1]}" for p in method.parameters]
        )
        signature = f"{method.name}({params})"

        source_color = {"PROJECT": "green", "LIBRARY": "yellow", "JDK": "dim"}.get(
            result.class_source.value, ""
        )
        table.add_row(
            signature,
            method.return_type.split(".")[-1],
            result.class_simple_name,
            f"[{source_color}]{result.class_source.value}[/]",
        )

    console.print(table)

    if response.total_pages > 1:
        console.print(
            f"\nPage {response.page + 1} of {response.total_pages}. "
            f"Use --page to navigate."
        )
    console.print()
