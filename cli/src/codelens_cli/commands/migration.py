"""Ratpack migration analysis commands."""

from typing import Optional

import typer
from rich.console import Console
from rich.table import Table

from codelens_cli.commands.common import (
    ensure_server_running,
    get_client,
    handle_api_errors,
)
from codelens_cli.output import is_tty, print_json

app = typer.Typer(
    name="migration",
    help="Analyze migration complexity and planning.",
    no_args_is_help=True,
)
console = Console()


def _tier_style(tier: str) -> str:
    """Get color style for complexity tier."""
    return {
        "LOW": "green",
        "MEDIUM": "yellow",
        "HIGH": "red",
        "CRITICAL": "bold red",
    }.get(tier, "white")


def _print_complexity_summary(data: dict) -> None:
    """Print complexity summary in human-readable format."""
    summary = data.get("summary", {})

    console.print("\n[bold]Migration Complexity Summary[/bold]")
    console.print(f"  Total Handlers: {summary.get('totalHandlers', 0)}")
    console.print(
        f"  Total Estimated Hours: {summary.get('totalEstimatedHours', 0):.1f}"
    )
    console.print(f"  Average Score: {summary.get('averageScore', 0):.1f}")
    console.print()

    # Tier breakdown
    breakdown = summary.get("tierBreakdown", {})
    if breakdown:
        table = Table(title="Complexity Tier Breakdown")
        table.add_column("Tier", style="cyan")
        table.add_column("Count", justify="right", style="yellow")

        for tier in ["LOW", "MEDIUM", "HIGH", "CRITICAL"]:
            count = breakdown.get(tier, 0)
            if count > 0:
                table.add_row(
                    f"[{_tier_style(tier)}]{tier}[/{_tier_style(tier)}]",
                    str(count),
                )

        console.print(table)

    console.print()


def _print_complexity_detail(data: dict) -> None:
    """Print complexity detail in human-readable format."""
    complexity = data.get("complexity", {})

    tier = complexity.get("tier", "LOW")
    console.print(f"\n[bold cyan]{complexity.get('classFqn', '')}[/bold cyan]")
    console.print(
        f"  Score: [{_tier_style(tier)}]{complexity.get('score', 0)}/100 ({tier})[/{_tier_style(tier)}]"
    )
    console.print(f"  Estimated Hours: {complexity.get('estimatedHours', 0):.1f}")
    console.print(f"  Migration Priority: {complexity.get('migrationPriority', 0)}")
    console.print()

    # Factors
    factors = complexity.get("factors", [])
    if factors:
        console.print("[bold]Complexity Factors[/bold]")
        for factor in factors:
            console.print(
                f"  [{_tier_style(tier)}]+{factor.get('points')} pts[/{_tier_style(tier)}] {factor.get('name')}"
            )
            console.print(f"       {factor.get('details')}")
        console.print()

    # Migration notes
    notes = complexity.get("migrationNotes", [])
    if notes:
        console.print("[bold]Migration Notes[/bold]")
        for note in notes:
            console.print(f"  [yellow]! {note}[/yellow]")
        console.print()

    # Blocked by
    blocked = complexity.get("blockedBy", [])
    if blocked:
        console.print("[bold]Blocked By (migrate first)[/bold]")
        for dep in blocked:
            console.print(f"  - {dep}")
        console.print()


def _print_migration_order(data: dict) -> None:
    """Print migration order in human-readable format."""
    order = data.get("order", [])
    total_hours = data.get("totalEstimatedHours", 0)

    if not order:
        console.print("[yellow]No handlers to migrate.[/yellow]")
        return

    console.print("\n[bold]Suggested Migration Order[/bold]")
    console.print(f"Total Estimated Hours: {total_hours:.1f}")
    console.print()

    table = Table(title=f"Migration Order ({len(order)} handlers)")
    table.add_column("#", justify="right", style="dim")
    table.add_column("Class", style="cyan")
    table.add_column("Tier", justify="center")
    table.add_column("Hours", justify="right")
    table.add_column("Reason", style="dim")

    cumulative_hours = 0.0
    for item in order:
        tier = item.get("tier", "LOW")
        hours = item.get("estimatedHours", 0)
        cumulative_hours += hours

        table.add_row(
            str(item.get("order", 0)),
            item.get("simpleName", ""),
            f"[{_tier_style(tier)}]{tier}[/{_tier_style(tier)}]",
            f"{hours:.1f}",
            item.get("reason", ""),
        )

    console.print(table)
    console.print(f"\n[dim]Cumulative time: {cumulative_hours:.1f} hours[/dim]")
    console.print()


@app.command(name="complexity")
def complexity(
    fqn: Optional[str] = typer.Argument(
        None, help="Fully qualified class name (optional)"
    ),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show complexity analysis for a class or project summary."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        if fqn:
            result = client.get_complexity(fqn)
            if json_output or not is_tty():
                print_json(result)
            else:
                _print_complexity_detail(result)
        else:
            result = client.get_complexity_summary()
            if json_output or not is_tty():
                print_json(result)
            else:
                _print_complexity_summary(result)


@app.command(name="order")
def migration_order(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show suggested migration order for handlers."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_migration_order()

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_migration_order(result)
