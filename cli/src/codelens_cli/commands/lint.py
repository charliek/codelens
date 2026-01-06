"""Lint and format commands for Kotlin code."""

from pathlib import Path
from typing import Optional

import typer
from rich.console import Console
from rich.table import Table

from codelens_cli.commands.common import ensure_server_running, get_client, handle_api_errors
from codelens_cli.models import (
    FormatFileResponse,
    FormatProjectResponse,
    LintFileResponse,
    LintProjectResponse,
)
from codelens_cli.output import is_tty, print_json

app = typer.Typer(
    name="lint",
    help="Lint and format Kotlin code using ktlint.",
    no_args_is_help=True,
)

console = Console()


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
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        if file:
            # Resolve file path relative to project
            file_path = Path(file)
            if not file_path.is_absolute():
                file_path = project_path / file_path
            result = client.lint_file(str(file_path))

            if json_output or not is_tty():
                print_json(result.model_dump(by_alias=True))
            else:
                _print_file_lint_result(result)

            # Exit with error if violations found
            if result.error_count > 0:
                raise typer.Exit(1)
        else:
            result = client.lint_project(
                pattern=pattern, include_tests=include_tests
            )

            if json_output or not is_tty():
                print_json(result.model_dump(by_alias=True))
            else:
                _print_project_lint_result(result)

            # Exit with error if violations found
            if result.total_error_count > 0:
                raise typer.Exit(1)


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
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        if file:
            # Resolve file path relative to project
            file_path = Path(file)
            if not file_path.is_absolute():
                file_path = project_path / file_path
            result = client.format_file(
                str(file_path), write_to_file=not dry_run
            )

            if json_output or not is_tty():
                print_json(result.model_dump(by_alias=True))
            else:
                _print_file_format_result(result, dry_run)
        else:
            result = client.format_project(
                pattern=pattern,
                include_tests=include_tests,
                dry_run=dry_run,
            )

            if json_output or not is_tty():
                print_json(result.model_dump(by_alias=True))
            else:
                _print_project_format_result(result, dry_run)


def _print_file_lint_result(result: LintFileResponse) -> None:
    """Print lint result for a single file."""
    # Show file path relative or shortened
    display_path = Path(result.file_path).name

    if result.error_count == 0:
        console.print(f"\n[green]No issues found[/green] in {display_path}")
        console.print(f"[dim]Checked in {result.duration_ms}ms[/dim]\n")
        return

    console.print(f"\n[bold]{display_path}[/bold] - [red]{result.error_count} issue(s)[/red]")
    console.print()

    table = Table(show_header=True, header_style="bold")
    table.add_column("Line", justify="right", style="cyan")
    table.add_column("Col", justify="right", style="cyan")
    table.add_column("Rule")
    table.add_column("Message")
    table.add_column("Fix", justify="center")

    for error in result.errors:
        can_fix = "[green]Yes[/]" if error.can_be_auto_corrected else "[dim]No[/]"
        table.add_row(
            str(error.line),
            str(error.col),
            error.rule_id,
            error.detail,
            can_fix,
        )

    console.print(table)
    console.print(f"\n[dim]Checked in {result.duration_ms}ms[/dim]\n")


def _print_project_lint_result(result: LintProjectResponse) -> None:
    """Print lint result for a project."""
    project_name = Path(result.project_path).name if result.project_path else "project"

    console.print(f"\n[bold]Lint Results for {project_name}[/bold]")
    console.print()

    if result.total_error_count == 0:
        console.print(
            f"[green]No issues found[/green] in {result.files_scanned} file(s)"
        )
        console.print(f"[dim]Checked in {result.duration_ms}ms[/dim]\n")
        return

    console.print(
        f"[red]{result.total_error_count} issue(s)[/red] in {result.files_with_errors} file(s) "
        f"({result.files_scanned} scanned)"
    )
    console.print()

    for file_result in result.file_results:
        # Make path relative to project
        try:
            rel_path = Path(file_result.file_path).relative_to(result.project_path)
        except ValueError:
            rel_path = Path(file_result.file_path).name

        console.print(f"[bold cyan]{rel_path}[/bold cyan] ({file_result.error_count} issue(s))")

        for error in file_result.errors:
            fix_hint = " [dim](auto-fixable)[/]" if error.can_be_auto_corrected else ""
            console.print(
                f"  [dim]{error.line}:{error.col}[/dim] {error.rule_id}: {error.detail}{fix_hint}"
            )

        console.print()

    console.print(f"[dim]Checked in {result.duration_ms}ms[/dim]\n")


def _print_file_format_result(result: FormatFileResponse, dry_run: bool) -> None:
    """Print format result for a single file."""
    display_path = Path(result.file_path).name

    if not result.has_changes:
        console.print(f"\n[green]No changes needed[/green] for {display_path}")
        console.print(f"[dim]Processed in {result.duration_ms}ms[/dim]\n")
        return

    if dry_run:
        console.print(f"\n[yellow]Would format[/yellow] {display_path}")
        if result.formatted_content:
            console.print("\n[dim]--- Formatted content ---[/dim]")
            console.print(result.formatted_content)
            console.print("[dim]--- End ---[/dim]")
    else:
        console.print(f"\n[green]Formatted[/green] {display_path}")

    if result.remaining_errors:
        console.print(f"\n[yellow]{len(result.remaining_errors)} issue(s) could not be auto-fixed:[/yellow]")
        for error in result.remaining_errors:
            console.print(f"  [dim]{error.line}:{error.col}[/dim] {error.rule_id}: {error.detail}")

    console.print(f"\n[dim]Processed in {result.duration_ms}ms[/dim]\n")


def _print_project_format_result(result: FormatProjectResponse, dry_run: bool) -> None:
    """Print format result for a project."""
    project_name = Path(result.project_path).name if result.project_path else "project"

    console.print(f"\n[bold]Format Results for {project_name}[/bold]")
    console.print()

    if result.files_with_changes == 0:
        console.print(
            f"[green]No changes needed[/green] in {result.files_scanned} file(s)"
        )
        console.print(f"[dim]Processed in {result.duration_ms}ms[/dim]\n")
        return

    action = "Would format" if dry_run else "Formatted"
    color = "yellow" if dry_run else "green"
    console.print(
        f"[{color}]{action} {result.files_with_changes} file(s)[/{color}] "
        f"({result.files_scanned} scanned)"
    )
    console.print()

    for file_path in result.files_formatted:
        # Make path relative to project
        try:
            rel_path = Path(file_path).relative_to(result.project_path)
        except ValueError:
            rel_path = Path(file_path).name
        console.print(f"  [{color}]{rel_path}[/{color}]")

    console.print(f"\n[dim]Processed in {result.duration_ms}ms[/dim]\n")
