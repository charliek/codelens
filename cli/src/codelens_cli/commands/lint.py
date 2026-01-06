"""Lint and format commands for Kotlin code."""

import asyncio
from pathlib import Path
from typing import Optional

import typer
from rich.console import Console
from rich.table import Table

from codelens_cli.client import CodeLensClient
from codelens_cli.container import ServiceContainer
from codelens_cli.errors import ExitCode
from codelens_cli.models import ProjectStatus
from codelens_cli.output import is_tty, print_json

app = typer.Typer(
    name="lint",
    help="Lint and format Kotlin code using ktlint.",
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


@app.command(name="check")
def lint_check(
    file: Optional[str] = typer.Argument(
        None, help="File to check (checks entire project if omitted)"
    ),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    pattern: Optional[str] = typer.Option(
        None, "--pattern", help="Glob pattern to filter files (e.g., '*.kt')"
    ),
    include_tests: bool = typer.Option(
        True, "--include-tests/--no-tests", help="Include test files"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Check Kotlin files for style issues using ktlint.

    If FILE is provided, checks only that file. Otherwise checks all Kotlin
    files in the project (optionally filtered by --pattern).

    Exit code is 1 if any style violations are found, 0 otherwise.
    """
    server, project_path = _ensure_server_running(project, json_output)
    client = _get_client(server)

    try:
        if file:
            # Resolve file path relative to project
            file_path = Path(file)
            if not file_path.is_absolute():
                file_path = project_path / file_path
            result = client.lint_file(str(file_path))

            if json_output or not is_tty():
                print_json(result)
            else:
                _print_file_lint_result(result)

            # Exit with error if violations found
            if result.get("errorCount", 0) > 0:
                raise typer.Exit(1)
        else:
            result = client.lint_project(
                pattern=pattern, include_tests=include_tests
            )

            if json_output or not is_tty():
                print_json(result)
            else:
                _print_project_lint_result(result)

            # Exit with error if violations found
            if result.get("totalErrorCount", 0) > 0:
                raise typer.Exit(1)

    except typer.Exit:
        raise
    except Exception as e:
        err_console.print(f"[red]Error:[/red] {e}")
        raise typer.Exit(ExitCode.CONNECTION_ERROR)


@app.command(name="format")
def lint_format(
    file: Optional[str] = typer.Argument(
        None, help="File to format (formats entire project if omitted)"
    ),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    pattern: Optional[str] = typer.Option(
        None, "--pattern", help="Glob pattern to filter files (e.g., '*.kt')"
    ),
    include_tests: bool = typer.Option(
        True, "--include-tests/--no-tests", help="Include test files"
    ),
    dry_run: bool = typer.Option(
        False, "--dry-run", "-n", help="Preview changes without modifying files"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Format Kotlin files using ktlint.

    If FILE is provided, formats only that file. Otherwise formats all Kotlin
    files in the project (optionally filtered by --pattern).

    Use --dry-run to preview what would be changed without modifying files.
    """
    server, project_path = _ensure_server_running(project, json_output)
    client = _get_client(server)

    try:
        if file:
            # Resolve file path relative to project
            file_path = Path(file)
            if not file_path.is_absolute():
                file_path = project_path / file_path
            result = client.format_file(
                str(file_path), write_to_file=not dry_run
            )

            if json_output or not is_tty():
                print_json(result)
            else:
                _print_file_format_result(result, dry_run)
        else:
            result = client.format_project(
                pattern=pattern,
                include_tests=include_tests,
                dry_run=dry_run,
            )

            if json_output or not is_tty():
                print_json(result)
            else:
                _print_project_format_result(result, dry_run)

    except Exception as e:
        err_console.print(f"[red]Error:[/red] {e}")
        raise typer.Exit(ExitCode.CONNECTION_ERROR)


def _print_file_lint_result(result: dict) -> None:
    """Print lint result for a single file."""
    file_path = result.get("filePath", "unknown")
    errors = result.get("errors", [])
    error_count = result.get("errorCount", 0)
    duration_ms = result.get("durationMs", 0)

    # Show file path relative or shortened
    display_path = Path(file_path).name

    if error_count == 0:
        console.print(f"\n[green]No issues found[/green] in {display_path}")
        console.print(f"[dim]Checked in {duration_ms}ms[/dim]\n")
        return

    console.print(f"\n[bold]{display_path}[/bold] - [red]{error_count} issue(s)[/red]")
    console.print()

    table = Table(show_header=True, header_style="bold")
    table.add_column("Line", justify="right", style="cyan")
    table.add_column("Col", justify="right", style="cyan")
    table.add_column("Rule")
    table.add_column("Message")
    table.add_column("Fix", justify="center")

    for error in errors:
        can_fix = "[green]Yes[/]" if error.get("canBeAutoCorrected") else "[dim]No[/]"
        table.add_row(
            str(error.get("line", "")),
            str(error.get("col", "")),
            error.get("ruleId", ""),
            error.get("detail", ""),
            can_fix,
        )

    console.print(table)
    console.print(f"\n[dim]Checked in {duration_ms}ms[/dim]\n")


def _print_project_lint_result(result: dict) -> None:
    """Print lint result for a project."""
    project_path = result.get("projectPath", "")
    file_results = result.get("fileResults", [])
    files_scanned = result.get("filesScanned", 0)
    files_with_errors = result.get("filesWithErrors", 0)
    total_errors = result.get("totalErrorCount", 0)
    duration_ms = result.get("durationMs", 0)

    project_name = Path(project_path).name if project_path else "project"

    console.print(f"\n[bold]Lint Results for {project_name}[/bold]")
    console.print()

    if total_errors == 0:
        console.print(
            f"[green]No issues found[/green] in {files_scanned} file(s)"
        )
        console.print(f"[dim]Checked in {duration_ms}ms[/dim]\n")
        return

    console.print(
        f"[red]{total_errors} issue(s)[/red] in {files_with_errors} file(s) "
        f"({files_scanned} scanned)"
    )
    console.print()

    for file_result in file_results:
        file_path = file_result.get("filePath", "")
        errors = file_result.get("errors", [])
        error_count = file_result.get("errorCount", 0)

        # Make path relative to project
        try:
            rel_path = Path(file_path).relative_to(project_path)
        except ValueError:
            rel_path = Path(file_path).name

        console.print(f"[bold cyan]{rel_path}[/bold cyan] ({error_count} issue(s))")

        for error in errors:
            line = error.get("line", "?")
            col = error.get("col", "?")
            rule = error.get("ruleId", "")
            detail = error.get("detail", "")
            can_fix = error.get("canBeAutoCorrected", False)

            fix_hint = " [dim](auto-fixable)[/]" if can_fix else ""
            console.print(
                f"  [dim]{line}:{col}[/dim] {rule}: {detail}{fix_hint}"
            )

        console.print()

    console.print(f"[dim]Checked in {duration_ms}ms[/dim]\n")


def _print_file_format_result(result: dict, dry_run: bool) -> None:
    """Print format result for a single file."""
    file_path = result.get("filePath", "unknown")
    has_changes = result.get("hasChanges", False)
    remaining_errors = result.get("remainingErrors", [])
    duration_ms = result.get("durationMs", 0)
    formatted_content = result.get("formattedContent")

    display_path = Path(file_path).name

    if not has_changes:
        console.print(f"\n[green]No changes needed[/green] for {display_path}")
        console.print(f"[dim]Processed in {duration_ms}ms[/dim]\n")
        return

    if dry_run:
        console.print(f"\n[yellow]Would format[/yellow] {display_path}")
        if formatted_content:
            console.print("\n[dim]--- Formatted content ---[/dim]")
            console.print(formatted_content)
            console.print("[dim]--- End ---[/dim]")
    else:
        console.print(f"\n[green]Formatted[/green] {display_path}")

    if remaining_errors:
        console.print(f"\n[yellow]{len(remaining_errors)} issue(s) could not be auto-fixed:[/yellow]")
        for error in remaining_errors:
            line = error.get("line", "?")
            col = error.get("col", "?")
            rule = error.get("ruleId", "")
            detail = error.get("detail", "")
            console.print(f"  [dim]{line}:{col}[/dim] {rule}: {detail}")

    console.print(f"\n[dim]Processed in {duration_ms}ms[/dim]\n")


def _print_project_format_result(result: dict, dry_run: bool) -> None:
    """Print format result for a project."""
    project_path = result.get("projectPath", "")
    files_formatted = result.get("filesFormatted", [])
    files_scanned = result.get("filesScanned", 0)
    files_with_changes = result.get("filesWithChanges", 0)
    duration_ms = result.get("durationMs", 0)

    project_name = Path(project_path).name if project_path else "project"

    console.print(f"\n[bold]Format Results for {project_name}[/bold]")
    console.print()

    if files_with_changes == 0:
        console.print(
            f"[green]No changes needed[/green] in {files_scanned} file(s)"
        )
        console.print(f"[dim]Processed in {duration_ms}ms[/dim]\n")
        return

    action = "Would format" if dry_run else "Formatted"
    color = "yellow" if dry_run else "green"
    console.print(
        f"[{color}]{action} {files_with_changes} file(s)[/{color}] "
        f"({files_scanned} scanned)"
    )
    console.print()

    for file_path in files_formatted:
        # Make path relative to project
        try:
            rel_path = Path(file_path).relative_to(project_path)
        except ValueError:
            rel_path = Path(file_path).name
        console.print(f"  [{color}]{rel_path}[/{color}]")

    console.print(f"\n[dim]Processed in {duration_ms}ms[/dim]\n")
