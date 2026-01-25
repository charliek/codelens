"""Dependency analysis commands."""

import json
from pathlib import Path
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
    name="deps",
    help="Analyze project-wide dependencies between handlers and services.",
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


def _type_style(class_type: str) -> str:
    """Get color style for class type."""
    return {
        "HANDLER": "cyan",
        "SERVICE": "blue",
        "REPOSITORY": "magenta",
        "UTILITY": "yellow",
        "OTHER": "white",
    }.get(class_type, "white")


def _print_summary(data: dict) -> None:
    """Print dependency analysis summary."""
    analysis = data.get("analysis", {})
    stats = analysis.get("stats", {})

    console.print("\n[bold]Dependency Analysis Summary[/bold]")
    console.print(f"  Total Handlers: {stats.get('totalHandlers', 0)}")
    console.print(f"  Total Dependencies: {stats.get('totalDependencies', 0)}")
    console.print(
        f"  Avg Dependencies/Handler: {stats.get('avgDependenciesPerHandler', 0):.1f}"
    )
    console.print(f"  Max Dependencies: {stats.get('maxDependencies', 0)}")

    cycle_count = stats.get("cycleCount", 0)
    if cycle_count > 0:
        console.print(f"  [red]Circular Dependencies: {cycle_count}[/red]")
    else:
        console.print("  [green]Circular Dependencies: 0[/green]")
    console.print()

    # Foundation classes
    foundation = analysis.get("foundationClasses", [])
    if foundation:
        console.print("[bold]Foundation Classes[/bold] (migrate these first)")
        table = Table(show_header=True, header_style="bold")
        table.add_column("Class", style="cyan")
        table.add_column("Type", style="dim")
        table.add_column("Dependents", justify="right", style="yellow")

        for cls in foundation[:10]:  # Top 10
            table.add_row(
                cls.get("simpleName", ""),
                cls.get("type", ""),
                str(cls.get("dependentCount", 0)),
            )

        console.print(table)
        console.print()

    # Quick wins
    quick_wins = analysis.get("quickWins", [])
    if quick_wins:
        console.print("[bold]Quick Wins[/bold] (easy starting points)")
        table = Table(show_header=True, header_style="bold")
        table.add_column("Handler", style="cyan")
        table.add_column("Dependencies", justify="right")
        table.add_column("Complexity", style="dim")

        for handler in quick_wins[:10]:  # Top 10
            tier = handler.get("complexity", "LOW")
            table.add_row(
                handler.get("simpleName", ""),
                str(handler.get("dependencyCount", 0)),
                f"[{_tier_style(tier)}]{tier}[/{_tier_style(tier)}]",
            )

        console.print(table)
        console.print()

    # Cycles
    cycles = analysis.get("cycles", [])
    if cycles:
        console.print(
            "[bold red]Circular Dependencies[/bold red] (refactor before migration)"
        )
        for cycle in cycles:
            console.print(f"  [red]- {cycle.get('description', '')}[/red]")
        console.print()

    # Handler tiers
    tiers = analysis.get("handlerTiers", [])
    if tiers:
        console.print("[bold]Handler Dependency Tiers[/bold]")
        for tier in tiers:
            tier_num = tier.get("tier", 0)
            count = tier.get("count", 0)
            desc = tier.get("description", "")
            console.print(f"  Tier {tier_num}: {count} handlers - {desc}")
        console.print()


def _print_full_analysis(data: dict) -> None:
    """Print full dependency analysis with all details."""
    _print_summary(data)

    analysis = data.get("analysis", {})

    # List all handlers in each tier
    tiers = analysis.get("handlerTiers", [])
    if tiers:
        console.print("[bold]Handlers by Tier[/bold]")
        for tier in tiers:
            tier_num = tier.get("tier", 0)
            handlers = tier.get("handlers", [])
            if handlers:
                console.print(f"\n  [cyan]Tier {tier_num}:[/cyan]")
                for handler in handlers:
                    console.print(f"    - {handler}")
        console.print()


def _print_foundation_classes(data: dict) -> None:
    """Print foundation classes."""
    foundation = data.get("foundationClasses", [])
    count = data.get("count", 0)

    if not foundation:
        console.print("[yellow]No foundation classes found.[/yellow]")
        return

    console.print(f"\n[bold]Foundation Classes[/bold] ({count} found)")
    console.print("These classes have many handler dependents - migrate them first.\n")

    table = Table(show_header=True, header_style="bold")
    table.add_column("Class", style="cyan")
    table.add_column("Type")
    table.add_column("Dependents", justify="right", style="yellow")
    table.add_column("Dependent Handlers")

    for cls in foundation:
        class_type = cls.get("type", "OTHER")
        dependent_handlers = cls.get("dependentHandlers", [])
        handlers_str = ", ".join(dependent_handlers[:5])
        if len(dependent_handlers) > 5:
            handlers_str += f" (+{len(dependent_handlers) - 5} more)"

        table.add_row(
            cls.get("simpleName", ""),
            f"[{_type_style(class_type)}]{class_type}[/{_type_style(class_type)}]",
            str(cls.get("dependentCount", 0)),
            handlers_str,
        )

    console.print(table)
    console.print()


def _print_quick_wins(data: dict) -> None:
    """Print quick wins."""
    quick_wins = data.get("quickWins", [])
    count = data.get("count", 0)

    if not quick_wins:
        console.print("[yellow]No quick wins found.[/yellow]")
        return

    console.print(f"\n[bold]Quick Wins[/bold] ({count} found)")
    console.print(
        "Handlers with few dependencies and low complexity - easy starting points.\n"
    )

    table = Table(show_header=True, header_style="bold")
    table.add_column("Handler", style="cyan")
    table.add_column("Dependencies", justify="right")
    table.add_column("Complexity")
    table.add_column("FQN", style="dim")

    for handler in quick_wins:
        tier = handler.get("complexity", "LOW")
        table.add_row(
            handler.get("simpleName", ""),
            str(handler.get("dependencyCount", 0)),
            f"[{_tier_style(tier)}]{tier}[/{_tier_style(tier)}]",
            handler.get("fqn", ""),
        )

    console.print(table)
    console.print()


@app.callback(invoke_without_command=True)
def deps_default(
    ctx: typer.Context,
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    full: bool = typer.Option(
        False, "--full", help="Show full analysis with all details"
    ),
    output_format: str = typer.Option(
        "table", "--format", "-f", help="Output format (table, dot, json)"
    ),
    output: Optional[str] = typer.Option(
        None, "--output", "-o", help="Output file path (for dot/json formats)"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show dependency analysis summary.

    Use subcommands for specific views:
      deps foundation - Show foundation classes
      deps quickwins  - Show quick win handlers
    """
    if ctx.invoked_subcommand is not None:
        return

    server, project_path = ensure_server_running(
        project, json_output or output_format == "json"
    )
    client = get_client(server)

    with handle_api_errors():
        if output_format == "dot":
            result = client.get_dependency_analysis(format="dot")
            if output:
                Path(output).write_text(result)
                console.print(f"DOT graph written to {output}")
                console.print(f"Render with: dot -Tpng {output} -o graph.png")
            else:
                typer.echo(result)
        elif json_output or output_format == "json":
            result = client.get_dependency_analysis()
            if output:
                Path(output).write_text(json.dumps(result, indent=2))
                console.print(f"JSON written to {output}")
            else:
                print_json(result)
        else:
            result = client.get_dependency_analysis()
            if full:
                _print_full_analysis(result)
            else:
                _print_summary(result)


@app.command(name="foundation")
def foundation_classes(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show foundation classes (most depended-on classes)."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_foundation_classes()

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_foundation_classes(result)


@app.command(name="quickwins")
def quick_wins(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show quick win handlers (few dependencies, low complexity)."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_quick_wins()

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_quick_wins(result)


@app.command(name="graph")
def dependency_graph(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    output_format: str = typer.Option(
        "table", "--format", "-f", help="Output format (table, dot, json)"
    ),
    output: Optional[str] = typer.Option(
        None, "--output", "-o", help="Output file path"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Get full dependency graph for visualization."""
    server, project_path = ensure_server_running(
        project, json_output or output_format == "json"
    )
    client = get_client(server)

    with handle_api_errors():
        if output_format == "dot":
            result = client.get_dependency_graph(format="dot")
            if output:
                Path(output).write_text(result)
                console.print(f"DOT graph written to {output}")
                console.print(f"Render with: dot -Tpng {output} -o graph.png")
            else:
                typer.echo(result)
        elif json_output or output_format == "json":
            result = client.get_dependency_graph()
            if output:
                Path(output).write_text(json.dumps(result, indent=2))
                console.print(f"JSON written to {output}")
            else:
                print_json(result)
        else:
            result = client.get_dependency_graph()
            graph = result.get("graph", {})

            nodes = graph.get("nodes", [])
            edges = graph.get("edges", [])
            cycles = graph.get("cycles", [])

            console.print("\n[bold]Dependency Graph[/bold]")
            console.print(f"  Nodes: {len(nodes)}")
            console.print(f"  Edges: {len(edges)}")
            console.print(
                f"  Acyclic: {'[green]Yes[/green]' if graph.get('isAcyclic') else '[red]No[/red]'}"
            )
            console.print()

            if cycles:
                console.print("[bold red]Cycles:[/bold red]")
                for cycle in cycles:
                    console.print(f"  - {cycle.get('description', '')}")
                console.print()

            # Show high-impact nodes
            high_impact = sorted(
                nodes, key=lambda n: n.get("inDegree", 0), reverse=True
            )[:10]
            if high_impact:
                console.print("[bold]High-Impact Nodes[/bold] (most dependents)")
                table = Table(show_header=True, header_style="bold")
                table.add_column("Node", style="cyan")
                table.add_column("Type")
                table.add_column("In", justify="right", style="green")
                table.add_column("Out", justify="right", style="yellow")
                table.add_column("Complexity")

                for node in high_impact:
                    class_type = node.get("type", "OTHER")
                    complexity = node.get("complexity")
                    complexity_str = (
                        f"[{_tier_style(complexity)}]{complexity}[/{_tier_style(complexity)}]"
                        if complexity
                        else "-"
                    )

                    table.add_row(
                        node.get("label", ""),
                        f"[{_type_style(class_type)}]{class_type}[/{_type_style(class_type)}]",
                        str(node.get("inDegree", 0)),
                        str(node.get("outDegree", 0)),
                        complexity_str,
                    )

                console.print(table)
                console.print()

            console.print(
                "[dim]Use --format dot -o graph.dot to export for Graphviz visualization[/dim]"
            )
            console.print()
