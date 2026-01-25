"""Ratpack handler analysis commands."""

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
    name="handlers",
    help="Analyze Ratpack handlers and their complexity.",
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


def _print_handler_list(data: dict, show_count: int | None = None) -> None:
    """Print handler list in human-readable format."""
    handlers = data.get("handlers", [])
    total = data.get("totalCount", len(handlers))
    display_count = show_count if show_count is not None else total

    if not handlers:
        console.print("[yellow]No handlers found.[/yellow]")
        return

    table = Table(title=f"Ratpack Handlers ({display_count} of {total} total)")
    table.add_column("Class", style="cyan")
    table.add_column("Type", style="blue")
    table.add_column("Tier", justify="center")
    table.add_column("Score", justify="right")
    table.add_column("@Inject", justify="center")
    table.add_column("Promise Ops", justify="right")
    table.add_column("Blocking", justify="center")

    for handler in handlers:
        tier = handler.get("complexityTier", "LOW")
        has_inject = handler.get("hasInjectAnnotation", False)
        table.add_row(
            handler.get("simpleName", ""),
            handler.get("handlerType", ""),
            f"[{_tier_style(tier)}]{tier}[/{_tier_style(tier)}]",
            str(handler.get("complexityScore", 0)),
            "[green]Yes[/green]" if has_inject else "[dim]No[/dim]",
            str(handler.get("promiseOperationCount", 0)),
            "[red]Yes[/red]" if handler.get("usesBlocking") else "[dim]No[/dim]",
        )

    console.print(table)


def _print_handler_detail(data: dict) -> None:
    """Print handler detail in human-readable format."""
    handler = data.get("handler", {})
    if not handler:
        console.print("[red]Handler not found.[/red]")
        return

    console.print(f"\n[bold cyan]{handler.get('fqn', '')}[/bold cyan]")
    console.print(f"  Package: {handler.get('packageName', '')}")
    console.print(f"  Type: [blue]{handler.get('handlerType', '')}[/blue]")

    if handler.get("superclass"):
        console.print(f"  Superclass: {handler.get('superclass')}")
    if handler.get("interfaces"):
        console.print(f"  Interfaces: {', '.join(handler.get('interfaces', []))}")

    # Complexity
    complexity = handler.get("complexity", {})
    tier = complexity.get("tier", "LOW")
    console.print("\n[bold]Complexity Analysis[/bold]")
    console.print(
        f"  Score: [{_tier_style(tier)}]{complexity.get('score', 0)}/100 ({tier})[/{_tier_style(tier)}]"
    )
    console.print(f"  Estimated Hours: {complexity.get('estimatedHours', 0):.1f}")

    factors = complexity.get("factors", [])
    if factors:
        console.print("  Factors:")
        for factor in factors:
            console.print(
                f"    - {factor.get('name')}: +{factor.get('points')} pts ({factor.get('details')})"
            )

    notes = complexity.get("migrationNotes", [])
    if notes:
        console.print("  Migration Notes:")
        for note in notes:
            console.print(f"    [yellow]! {note}[/yellow]")

    # Promise Analysis
    promise = handler.get("promiseAnalysis", {})
    if promise.get("totalOperationCount", 0) > 0:
        console.print("\n[bold]Promise Usage[/bold]")
        console.print(f"  Total Operations: {promise.get('totalOperationCount', 0)}")
        console.print(f"  Max Chain Depth: {promise.get('maxChainDepth', 0)}")
        console.print(
            f"  Uses Blocking: {'[red]Yes[/red]' if promise.get('usesBlocking') else '[dim]No[/dim]'}"
        )
        console.print(
            f"  Uses Async: {'[yellow]Yes[/yellow]' if promise.get('usesAsync') else '[dim]No[/dim]'}"
        )
        console.print(
            f"  Uses Fork: {'[yellow]Yes[/yellow]' if promise.get('usesFork') else '[dim]No[/dim]'}"
        )

    # Injected Dependencies
    deps = handler.get("injectedDependencies", [])
    if deps:
        console.print("\n[bold]Injected Dependencies[/bold]")
        for dep in deps:
            console.print(
                f"  - {dep.get('name')}: {dep.get('typeFqn')} ({dep.get('injectionType')})"
            )

    console.print()


@app.command(name="list")
def list_handlers(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    handler_type: Optional[str] = typer.Option(
        None,
        "--type",
        "-t",
        help="Filter by handler type (HANDLER, CHAIN_ACTION, INLINE_HANDLER, GROOVY_HANDLER)",
    ),
    tier: Optional[str] = typer.Option(
        None,
        "--tier",
        help="Filter by complexity tier (LOW, MEDIUM, HIGH, CRITICAL)",
    ),
    missing_inject: bool = typer.Option(
        False,
        "--missing-inject",
        "-I",
        help="Only show handlers without @Inject annotation",
    ),
    include_libraries: bool = typer.Option(
        False, "--include-libraries", "-L", help="Include library handlers"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """List Ratpack handlers in the codebase."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.list_handlers(
            handler_type=handler_type,
            tier=tier,
            include_libraries=include_libraries,
        )

        # Apply client-side filter for missing @Inject
        if missing_inject:
            handlers = result.get("handlers", [])
            filtered = [h for h in handlers if not h.get("hasInjectAnnotation", False)]
            result["handlers"] = filtered
            # Keep original totalCount for display context
            display_count = len(filtered)
        else:
            display_count = None

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_handler_list(result, show_count=display_count)


@app.command(name="show")
def show_handler(
    fqn: str = typer.Argument(help="Fully qualified handler class name"),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show detailed information about a Ratpack handler."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_handler(fqn)

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_handler_detail(result)
