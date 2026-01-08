"""Shared utilities for CLI commands."""

import asyncio
from contextlib import contextmanager
from pathlib import Path
from typing import Generator, Optional

import httpx
import typer
from pydantic import ValidationError
from rich.console import Console

from codelens_cli.client import CodeLensClient
from codelens_cli.container import ServiceContainer
from codelens_cli.errors import ExitCode
from codelens_cli.models import ProjectStatus, ServerState
from codelens_cli.output import is_tty

err_console = Console(stderr=True)


@contextmanager
def handle_api_errors() -> Generator[None, None, None]:
    """Context manager for handling API errors with appropriate exit codes.

    Usage:
        with handle_api_errors():
            result = client.get_class(fqn)

    Note: Does not catch typer.Exit or SystemExit to preserve explicit exit codes.
    """
    try:
        yield
    except (typer.Exit, SystemExit):
        raise  # Preserve explicit exits
    except ValidationError as e:
        err_console.print(f"[red]Invalid server response:[/red] {e}")
        raise typer.Exit(ExitCode.GENERAL_ERROR)
    except httpx.TimeoutException as e:
        err_console.print(f"[red]Timeout:[/red] Request timed out: {e}")
        raise typer.Exit(ExitCode.TIMEOUT)
    except httpx.ConnectError as e:
        err_console.print(f"[red]Connection error:[/red] Could not connect to server: {e}")
        raise typer.Exit(ExitCode.CONNECTION_ERROR)
    except httpx.HTTPStatusError as e:
        if e.response.status_code == 404:
            err_console.print(f"[red]Not found:[/red] {e.response.text}")
            raise typer.Exit(ExitCode.GENERAL_ERROR)
        elif e.response.status_code >= 500:
            err_console.print(f"[red]Server error:[/red] {e.response.text}")
            raise typer.Exit(ExitCode.SERVER_ERROR)
        else:
            err_console.print(f"[red]Error:[/red] {e.response.text}")
            raise typer.Exit(ExitCode.GENERAL_ERROR)
    except Exception as e:
        err_console.print(f"[red]Error:[/red] {e}")
        raise typer.Exit(ExitCode.GENERAL_ERROR)


def ensure_server_running(
    project: Optional[str], json_output: bool = False
) -> tuple[ServerState, Path]:
    """Ensure a server is running for the project.

    Args:
        project: Optional project directory path
        json_output: Whether JSON output mode is enabled (suppresses status messages)

    Returns:
        Tuple of (ServerState, project_path)

    Raises:
        typer.Exit: If server fails to start
    """
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


def get_client(server: ServerState) -> CodeLensClient:
    """Create a client for the server.

    Args:
        server: Server state with host and port

    Returns:
        Configured CodeLensClient instance
    """
    return CodeLensClient(server.host, server.port)
