"""Anti-pattern detection commands."""

from typing import Optional

import typer
from rich.console import Console
from rich.panel import Panel
from rich.table import Table

from codelens_cli.commands.common import (
    ensure_server_running,
    get_client,
    handle_api_errors,
)
from codelens_cli.output import is_tty, print_json

app = typer.Typer(
    name="antipatterns",
    help="Detect anti-patterns in Ratpack code.",
    no_args_is_help=True,
)
console = Console()


def _severity_style(severity: str) -> str:
    """Get color style for severity level."""
    return {
        "INFO": "blue",
        "WARNING": "yellow",
        "ERROR": "red",
        "CRITICAL": "bold red",
    }.get(severity, "white")


def _print_antipattern_summary(data: dict) -> None:
    """Print anti-pattern summary in human-readable format."""
    summary = data.get("summary", {})
    total = summary.get("totalCount", 0)

    if total == 0:
        console.print("[green]No anti-patterns detected.[/green]")
        return

    # Severity breakdown
    count_by_severity = summary.get("countBySeverity", {})
    severity_parts = []
    for sev in ["CRITICAL", "ERROR", "WARNING", "INFO"]:
        count = count_by_severity.get(sev, 0)
        if count > 0:
            style = _severity_style(sev)
            severity_parts.append(f"[{style}]{count} {sev}[/{style}]")

    console.print(f"\n[bold]Anti-Pattern Summary:[/bold] {total} issues found")
    if severity_parts:
        console.print(f"  Severity: {', '.join(severity_parts)}")

    # Type breakdown
    count_by_type = summary.get("countByType", {})
    if count_by_type:
        console.print("\n[bold]By Type:[/bold]")
        type_table = Table(show_header=False, box=None, padding=(0, 2))
        type_table.add_column("Type", style="cyan")
        type_table.add_column("Count", justify="right")
        for pattern_type, count in sorted(count_by_type.items(), key=lambda x: -x[1]):
            type_table.add_row(pattern_type, str(count))
        console.print(type_table)

    # Worst offenders
    offenders = summary.get("worstOffenders", [])
    if offenders:
        console.print("\n[bold]Top Classes with Issues:[/bold]")
        offender_table = Table(show_header=True, box=None)
        offender_table.add_column("Class", style="cyan")
        offender_table.add_column("Total", justify="right")
        offender_table.add_column("Critical", justify="right", style="bold red")
        offender_table.add_column("Error", justify="right", style="red")

        for offender in offenders[:10]:
            offender_table.add_row(
                offender.get("classFqn", "").split(".")[-1],  # Simple name
                str(offender.get("count", 0)),
                (
                    str(offender.get("criticalCount", 0))
                    if offender.get("criticalCount", 0) > 0
                    else "-"
                ),
                (
                    str(offender.get("errorCount", 0))
                    if offender.get("errorCount", 0) > 0
                    else "-"
                ),
            )
        console.print(offender_table)

    # Instance details
    instances = summary.get("instances", [])
    if instances:
        console.print(f"\n[bold]All Issues ({len(instances)}):[/bold]")
        for instance in instances:
            severity = instance.get("severity", "INFO")
            style = _severity_style(severity)
            class_name = instance.get("classFqn", "").split(".")[-1]
            method = instance.get("methodName")
            location = f"{class_name}.{method}" if method else class_name

            console.print(
                f"\n[{style}][{severity}][/{style}] {instance.get('type', '')} in [cyan]{location}[/cyan]"
            )
            console.print(f"  {instance.get('reason', '')}")
            console.print(
                f"  [dim]Recommendation:[/dim] {instance.get('recommendation', '')}"
            )

    console.print()


def _print_class_antipatterns(data: dict) -> None:
    """Print anti-patterns for a specific class."""
    fqn = data.get("classFqn", "")
    antipatterns = data.get("antiPatterns", [])
    total = data.get("totalCount", 0)

    console.print(f"\n[bold cyan]{fqn}[/bold cyan]")

    if total == 0:
        console.print("[green]No anti-patterns detected in this class.[/green]\n")
        return

    console.print(f"[bold]{total} issue(s) found[/bold]\n")

    for instance in antipatterns:
        severity = instance.get("severity", "INFO")
        style = _severity_style(severity)
        method = instance.get("methodName")
        confidence = instance.get("confidence", 0)

        console.print(
            Panel(
                f"[bold]{instance.get('type', '')}[/bold]\n\n"
                f"[bold]Location:[/bold] {method or 'class level'}\n"
                f"[bold]Confidence:[/bold] {confidence:.0%}\n\n"
                f"[bold]Issue:[/bold]\n{instance.get('reason', '')}\n\n"
                f"[bold]Recommendation:[/bold]\n{instance.get('recommendation', '')}\n\n"
                f"[bold]Example Fix:[/bold]\n[dim]{instance.get('fixExample', 'N/A')}[/dim]",
                title=f"[{style}]{severity}[/{style}]",
                border_style=style,
            )
        )

    console.print()


@app.command(name="scan")
def scan_antipatterns(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    severity: Optional[str] = typer.Option(
        None,
        "--severity",
        "-s",
        help="Filter by severity (INFO, WARNING, ERROR, CRITICAL)",
    ),
    pattern_type: Optional[str] = typer.Option(
        None,
        "--type",
        "-t",
        help="Filter by anti-pattern type (BLOCKING_JDBC, THREAD_SLEEP, etc.)",
    ),
    include_libraries: bool = typer.Option(
        False, "--include-libraries", "-L", help="Include library classes"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Scan for anti-patterns in the codebase."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_antipatterns(
            severity=severity,
            pattern_type=pattern_type,
            include_libraries=include_libraries,
        )

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_antipattern_summary(result)


@app.command(name="show")
def show_class_antipatterns(
    fqn: str = typer.Argument(help="Fully qualified class name"),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show anti-patterns for a specific class."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_class_antipatterns(fqn)

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_class_antipatterns(result)
