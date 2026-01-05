"""Output formatting utilities."""

import json
import sys
from typing import Any

from rich.console import Console
from rich.table import Table


def is_tty() -> bool:
    """Check if stdout is a TTY."""
    return sys.stdout.isatty()


def get_console() -> Console:
    """Get a Rich console for output."""
    return Console()


def print_json(data: Any) -> None:
    """Print data as JSON."""
    print(json.dumps(data, indent=2, default=str))


def print_server_status(server: dict, console: Console | None = None) -> None:
    """Print server status in a nice format."""
    console = console or get_console()

    status_color = {
        "READY": "green",
        "STARTING": "yellow",
        "LOADING": "yellow",
        "ERROR": "red",
    }.get(server.get("status", ""), "white")

    console.print(f"\n[bold]CodeLens Server[/bold]")
    console.print()

    table = Table(show_header=False, box=None, padding=(0, 2))
    table.add_column("Key", style="dim")
    table.add_column("Value")

    table.add_row("Project:", server.get("projectName", "unknown"))
    table.add_row("Path:", server.get("projectPath", "unknown"))
    table.add_row("Status:", f"[{status_color}]{server.get('status', 'unknown')}[/]")
    table.add_row("Port:", str(server.get("port", "unknown")))
    table.add_row("Mode:", server.get("serverMode", "unknown"))

    if uptime := server.get("uptime"):
        table.add_row("Uptime:", uptime)
    if idle := server.get("idleDuration"):
        table.add_row("Idle:", idle)
    if timeout := server.get("idleTimeout"):
        table.add_row("Idle timeout:", timeout)

    console.print(table)
    console.print()


def print_project_info(project: dict, console: Console | None = None) -> None:
    """Print project info in a nice format."""
    console = console or get_console()

    status_color = {
        "READY": "green",
        "LOADING": "yellow",
        "ERROR": "red",
    }.get(project.get("status", ""), "white")

    console.print(f"\n[bold]{project.get('name', 'unknown')}[/bold]")
    console.print()

    table = Table(show_header=False, box=None, padding=(0, 2))
    table.add_column("Key", style="dim")
    table.add_column("Value")

    table.add_row("Path:", project.get("path", "unknown"))
    table.add_row("Status:", f"[{status_color}]{project.get('status', 'unknown')}[/]")

    if class_count := project.get("classCount"):
        table.add_row("Classes:", str(class_count))
    if handler_count := project.get("handlerCount"):
        table.add_row("Handlers:", str(handler_count))
    if scanned := project.get("scannedAt"):
        table.add_row("Scanned:", scanned)

    console.print(table)
    console.print()
