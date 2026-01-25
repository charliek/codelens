"""Ratpack Promise analysis commands."""

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
    name="promises",
    help="Analyze Ratpack Promise usage patterns.",
    no_args_is_help=True,
)
console = Console()


def _print_promise_summary(data: dict) -> None:
    """Print Promise summary in human-readable format."""
    summary = data.get("summary", {})

    console.print("\n[bold]Promise Usage Summary[/bold]")
    console.print(f"  Classes Using Promises: {summary.get('classesUsingPromises', 0)}")
    console.print()

    # Stats table
    table = Table(title="Promise Operation Counts")
    table.add_column("Operation", style="cyan")
    table.add_column("Count", justify="right", style="yellow")

    table.add_row("Blocking.get()", str(summary.get("blockingGetCount", 0)))
    table.add_row("Promise.async()", str(summary.get("promiseAsyncCount", 0)))
    table.add_row("Execution.fork()", str(summary.get("executionForkCount", 0)))
    table.add_row("ParallelBatch", str(summary.get("parallelBatchCount", 0)))
    table.add_row("Promise Operators", str(summary.get("operatorCount", 0)))

    console.print(table)

    # Breakdown by type
    breakdown = summary.get("operationBreakdown", {})
    if breakdown:
        console.print("\n[bold]Operation Breakdown[/bold]")
        for op_type, count in sorted(breakdown.items(), key=lambda x: -x[1]):
            console.print(f"  {op_type}: {count}")

    # Top complex classes
    top_classes = summary.get("topComplexClasses", [])
    if top_classes:
        console.print("\n[bold]Top Classes by Promise Complexity[/bold]")
        table = Table()
        table.add_column("Class", style="cyan")
        table.add_column("Ops", justify="right")
        table.add_column("Max Depth", justify="right")
        table.add_column("Blocking", justify="center")

        for cls in top_classes[:10]:
            table.add_row(
                cls.get("classFqn", "").split(".")[-1],
                str(cls.get("totalOperationCount", 0)),
                str(cls.get("maxChainDepth", 0)),
                "[red]Yes[/red]" if cls.get("usesBlocking") else "[dim]No[/dim]",
            )
        console.print(table)

    console.print()


def _print_promise_usage(data: dict) -> None:
    """Print Promise usage for a class in human-readable format."""
    usage = data.get("usage", {})

    console.print(f"\n[bold cyan]{usage.get('classFqn', '')}[/bold cyan]")
    console.print(f"  Total Operations: {usage.get('totalOperationCount', 0)}")
    console.print(f"  Max Chain Depth: {usage.get('maxChainDepth', 0)}")
    console.print()

    # Flags
    console.print("[bold]Patterns Used[/bold]")
    console.print(
        f"  Blocking: {'[red]Yes[/red]' if usage.get('usesBlocking') else '[dim]No[/dim]'}"
    )
    console.print(
        f"  Async: {'[yellow]Yes[/yellow]' if usage.get('usesAsync') else '[dim]No[/dim]'}"
    )
    console.print(
        f"  Fork: {'[yellow]Yes[/yellow]' if usage.get('usesFork') else '[dim]No[/dim]'}"
    )
    console.print(
        f"  ParallelBatch: {'[yellow]Yes[/yellow]' if usage.get('usesParallelBatch') else '[dim]No[/dim]'}"
    )

    # Promise-returning methods
    methods = usage.get("promiseReturningMethods", [])
    if methods:
        console.print("\n[bold]Promise-Returning Methods[/bold]")
        for method in methods:
            console.print(f"  - {method}()")

    # Operations
    ops = usage.get("operations", [])
    if ops:
        console.print("\n[bold]Promise Operations[/bold]")
        table = Table()
        table.add_column("Type", style="cyan")
        table.add_column("Method", style="blue")
        table.add_column("Chain Depth", justify="right")

        for op in ops:
            table.add_row(
                op.get("operationType", ""),
                op.get("methodName", ""),
                str(op.get("chainDepth", 0)),
            )
        console.print(table)

    console.print()


def _print_promise_search(data: dict) -> None:
    """Print Promise search results in human-readable format."""
    results = data.get("results", [])
    total = data.get("totalCount", len(results))

    if not results:
        console.print("[yellow]No matching classes found.[/yellow]")
        return

    table = Table(title=f"Promise Usage Search Results ({total} found)")
    table.add_column("Class", style="cyan")
    table.add_column("Ops", justify="right")
    table.add_column("Depth", justify="right")
    table.add_column("Blocking", justify="center")
    table.add_column("Async", justify="center")
    table.add_column("Fork", justify="center")

    for usage in results:
        table.add_row(
            usage.get("classFqn", "").split(".")[-1],
            str(usage.get("totalOperationCount", 0)),
            str(usage.get("maxChainDepth", 0)),
            "[red]Yes[/red]" if usage.get("usesBlocking") else "[dim]No[/dim]",
            "[yellow]Yes[/yellow]" if usage.get("usesAsync") else "[dim]No[/dim]",
            "[yellow]Yes[/yellow]" if usage.get("usesFork") else "[dim]No[/dim]",
        )

    console.print(table)


@app.command(name="summary")
def promise_summary(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    include_libraries: bool = typer.Option(
        False, "--include-libraries", "-L", help="Include library classes"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show project-wide Promise usage summary."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_promise_summary(include_libraries=include_libraries)

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_promise_summary(result)


@app.command(name="show")
def show_promise_usage(
    fqn: str = typer.Argument(help="Fully qualified class name"),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show Promise usage for a specific class."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_promise_usage(fqn)

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_promise_usage(result)


@app.command(name="search")
def search_promises(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    blocking: Optional[bool] = typer.Option(
        None, "--blocking/--no-blocking", help="Filter by Blocking usage"
    ),
    async_usage: Optional[bool] = typer.Option(
        None, "--async/--no-async", help="Filter by async usage"
    ),
    fork: Optional[bool] = typer.Option(
        None, "--fork/--no-fork", help="Filter by fork usage"
    ),
    min_operations: int = typer.Option(0, "--min-ops", help="Minimum operation count"),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Search for classes with specific Promise usage patterns."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.search_promises(
            uses_blocking=blocking,
            uses_async=async_usage,
            uses_fork=fork,
            min_operations=min_operations,
        )

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_promise_search(result)
