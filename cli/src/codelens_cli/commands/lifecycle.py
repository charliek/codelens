"""Server lifecycle commands: start, stop, status, restart, refresh."""

import asyncio
from pathlib import Path
from typing import Optional

import typer
from rich.console import Console

from codelens_cli.client import CodeLensClient
from codelens_cli.container import ServiceContainer
from codelens_cli.errors import ExitCode
from codelens_cli.models import ProjectStatus, ServerMode
from codelens_cli.output import is_tty, print_json, print_server_status
from codelens_cli.services import ProjectService, ServerService

app = typer.Typer()
console = Console()
err_console = Console(stderr=True)


def _get_services() -> tuple[ServerService, ProjectService]:
    """Get service instances from the container."""
    return ServiceContainer.server_service(), ServiceContainer.project_service()


@app.command()
def start(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    port: Optional[int] = typer.Option(None, "--port", help="Port to use"),
    mode: Optional[str] = typer.Option(
        None, "--mode", help="Server mode: gradle or jar"
    ),
    project_java: Optional[str] = typer.Option(
        None,
        "--project-java",
        help="Java home for target project's Gradle (auto-detected if not specified)",
    ),
    timeout: int = typer.Option(60, "--timeout", help="Startup timeout in seconds"),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Start the CodeLens server for a project."""
    server_service, project_service = _get_services()
    project_path = project_service.get_project_path(project)

    # Parse mode
    server_mode = ServerMode(mode) if mode else None

    # Parse project Java home
    project_java_home = Path(project_java) if project_java else None

    # Check if already running
    existing = server_service.find_server(project_path)
    if existing and existing.status == ProjectStatus.READY:
        if json_output or not is_tty():
            print_json(existing.model_dump(by_alias=True))
        else:
            console.print(
                f"[yellow]Server already running for {project_path.name}[/yellow]"
            )
            print_server_status(existing.model_dump(by_alias=True, mode="json"), console)
        return

    if not json_output and is_tty():
        err_console.print(
            f"Starting CodeLens server for [cyan]{project_path.name}[/cyan]..."
        )

    try:
        server = asyncio.run(
            server_service.start_server(
                project_path,
                mode=server_mode,
                port=port,
                timeout=timeout,
                project_java_home=project_java_home,
            )
        )

        if json_output or not is_tty():
            print_json(server.model_dump(by_alias=True))
        else:
            console.print(f"[green]✓[/green] Server ready")
            print_server_status(server.model_dump(by_alias=True, mode="json"), console)

    except TimeoutError:
        err_console.print(f"[red]Error:[/red] Server did not start within {timeout}s")
        err_console.print(f"\nCheck logs: [cyan]~/.cache/codelens/logs/[/cyan]")
        raise typer.Exit(ExitCode.TIMEOUT)
    except Exception as e:
        err_console.print(f"[red]Error:[/red] {e}")
        raise typer.Exit(ExitCode.SERVER_ERROR)


@app.command()
def stop(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    force: bool = typer.Option(
        False, "--force", help="Force kill if graceful shutdown fails"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Stop the CodeLens server for a project."""
    server_service, project_service = _get_services()
    project_path = project_service.get_project_path(project)

    stopped = server_service.stop_server(project_path, force=force)

    result = {"stopped": stopped, "project": str(project_path)}

    if json_output or not is_tty():
        print_json(result)
    else:
        if stopped:
            console.print(f"[green]✓[/green] Server stopped")
        else:
            console.print(f"[yellow]No server running for {project_path.name}[/yellow]")


@app.command()
def status(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show server status for a project."""
    server_service, project_service = _get_services()
    project_path = project_service.get_project_path(project)

    server = server_service.find_server(project_path)

    if server is None:
        if json_output or not is_tty():
            print_json({"running": False, "project": str(project_path)})
        else:
            console.print(f"[yellow]No server running for {project_path.name}[/yellow]")
            console.print(f"\nStart with: [cyan]codelens start[/cyan]")
        return

    # Get live info from server
    try:
        client = CodeLensClient(server.host, server.port)
        info = client.info()
        # Merge live info into server state
        server_dict = server.model_dump(by_alias=True, mode="json")
        server_dict.update(info)
    except Exception:
        server_dict = server.model_dump(by_alias=True, mode="json")

    if json_output or not is_tty():
        print_json(server_dict)
    else:
        print_server_status(server_dict, console)


@app.command()
def restart(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    mode: Optional[str] = typer.Option(
        None, "--mode", help="Server mode: gradle or jar"
    ),
    project_java: Optional[str] = typer.Option(
        None,
        "--project-java",
        help="Java home for target project's Gradle (auto-detected if not specified)",
    ),
    timeout: int = typer.Option(60, "--timeout", help="Startup timeout in seconds"),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Restart the CodeLens server for a project."""
    server_service, project_service = _get_services()
    project_path = project_service.get_project_path(project)

    # Parse mode
    server_mode = ServerMode(mode) if mode else None

    # Parse project Java home
    project_java_home = Path(project_java) if project_java else None

    if not json_output and is_tty():
        err_console.print("Restarting server...")

    server_service.stop_server(project_path)

    try:
        server = asyncio.run(
            server_service.start_server(
                project_path,
                mode=server_mode,
                timeout=timeout,
                project_java_home=project_java_home,
            )
        )

        if json_output or not is_tty():
            print_json(server.model_dump(by_alias=True))
        else:
            console.print(f"[green]✓[/green] Server restarted")
            print_server_status(server.model_dump(by_alias=True, mode="json"), console)

    except TimeoutError:
        err_console.print(f"[red]Error:[/red] Server did not start within {timeout}s")
        err_console.print(f"\nCheck logs: [cyan]~/.cache/codelens/logs/[/cyan]")
        raise typer.Exit(ExitCode.TIMEOUT)
    except Exception as e:
        err_console.print(f"[red]Error:[/red] {e}")
        raise typer.Exit(ExitCode.SERVER_ERROR)


@app.command()
def refresh(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Refresh the project scan (after code changes)."""
    server_service, project_service = _get_services()
    project_path = project_service.get_project_path(project)

    server = server_service.find_server(project_path)
    if server is None:
        err_console.print(f"[red]Error:[/red] No server running for {project_path.name}")
        err_console.print(f"\nStart with: [cyan]codelens start[/cyan]")
        raise typer.Exit(ExitCode.NOT_RUNNING)

    if not json_output and is_tty():
        err_console.print("Refreshing...")

    try:
        result = project_service.refresh_project(project_path, server)

        if json_output or not is_tty():
            print_json(result.model_dump(by_alias=True))
        else:
            console.print(f"[green]✓[/green] Refreshed")

    except Exception as e:
        err_console.print(f"[red]Error:[/red] {e}")
        raise typer.Exit(ExitCode.SERVER_ERROR)


@app.command(name="list")
def list_servers(
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """List all running CodeLens servers."""
    from rich.table import Table

    server_service = ServerService()
    servers = server_service.list_servers()

    if json_output or not is_tty():
        print_json(
            {"servers": [server.model_dump(by_alias=True) for server in servers]}
        )
        return

    if not servers:
        console.print("[yellow]No CodeLens servers running[/yellow]")
        return

    table = Table(title="Running CodeLens Servers")
    table.add_column("Project", style="cyan")
    table.add_column("Port")
    table.add_column("Status")
    table.add_column("Mode")
    table.add_column("Path", style="dim")

    for server in servers:
        status_style = {
            "READY": "green",
            "STARTING": "yellow",
        }.get(server.status.value, "white")

        table.add_row(
            server.project_name,
            str(server.port),
            f"[{status_style}]{server.status.value}[/]",
            server.server_mode.value,
            str(server.project_path),
        )

    console.print(table)
