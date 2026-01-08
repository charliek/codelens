"""Route analysis commands."""

from typing import Optional

import typer
from rich.console import Console
from rich.table import Table
from rich.tree import Tree

from codelens_cli.commands.common import ensure_server_running, get_client, handle_api_errors
from codelens_cli.output import is_tty, print_json

app = typer.Typer(
    name="routes",
    help="Analyze Ratpack routes and chain definitions.",
    no_args_is_help=True,
)
console = Console()


def _method_style(method: str) -> str:
    """Get color style for HTTP method."""
    return {
        "GET": "green",
        "POST": "blue",
        "PUT": "yellow",
        "PATCH": "yellow",
        "DELETE": "red",
        "OPTIONS": "dim",
        "HEAD": "dim",
        "ALL": "magenta",
    }.get(method, "white")


def _print_routes_summary(data: dict) -> None:
    """Print routes summary in human-readable format."""
    summary = data.get("summary", {})
    total = summary.get("totalRoutes", 0)
    unique_paths = summary.get("uniquePaths", 0)

    if total == 0:
        console.print("[yellow]No routes found.[/yellow]")
        return

    console.print(f"\n[bold]Route Summary:[/bold] {total} routes ({unique_paths} unique paths)")

    # Method breakdown
    routes_by_method = summary.get("routesByMethod", {})
    if routes_by_method:
        method_parts = []
        for method in ["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD", "ALL"]:
            count = routes_by_method.get(method, 0)
            if count > 0:
                style = _method_style(method)
                method_parts.append(f"[{style}]{count} {method}[/{style}]")
        if method_parts:
            console.print(f"  Methods: {', '.join(method_parts)}")

    # Routes table
    routes = summary.get("routes", [])
    if routes:
        console.print("\n[bold]Routes:[/bold]")
        table = Table(show_header=True, box=None)
        table.add_column("Method", style="bold", width=8)
        table.add_column("Path", style="cyan")
        table.add_column("Handler", style="dim")
        table.add_column("Chain", style="dim")

        for route in routes:
            method = route.get("method", "ALL")
            style = _method_style(method)
            table.add_row(
                f"[{style}]{method}[/{style}]",
                route.get("pathPattern", ""),
                route.get("handlerSimpleName", "-"),
                route.get("chainFqn", "").split(".")[-1],
            )

        console.print(table)

    # Chain classes
    chains = summary.get("chainClasses", [])
    if chains:
        console.print("\n[bold]Chain Classes:[/bold]")
        chain_table = Table(show_header=True, box=None)
        chain_table.add_column("Class", style="cyan")
        chain_table.add_column("Routes", justify="right")
        chain_table.add_column("Prefix", style="dim")

        for chain in chains:
            chain_table.add_row(
                chain.get("simpleName", ""),
                str(chain.get("routeCount", 0)),
                chain.get("pathPrefix", "-") or "-",
            )

        console.print(chain_table)

    console.print()


def _print_route_tree(data: dict) -> None:
    """Print route tree in human-readable format."""
    tree_data = data.get("tree", {})

    if not tree_data:
        console.print("[yellow]No routes found.[/yellow]")
        return

    console.print("\n[bold]Route Tree:[/bold]")

    def build_tree(node: dict, tree: Tree) -> None:
        """Recursively build the tree."""
        for route in node.get("routes", []):
            method = route.get("method", "ALL")
            style = _method_style(method)
            handler = route.get("handlerSimpleName", "")
            tree.add(f"[{style}]{method}[/{style}] {handler or '-'}")

        for child in node.get("children", []):
            segment = child.get("segment", "")
            child_tree = tree.add(f"[cyan]/{segment}[/cyan]")
            build_tree(child, child_tree)

    root_segment = tree_data.get("fullPath", "/")
    root_tree = Tree(f"[bold cyan]{root_segment}[/bold cyan]")
    build_tree(tree_data, root_tree)
    console.print(root_tree)
    console.print()


def _print_spring_mappings(data: dict) -> None:
    """Print Spring mapping equivalents."""
    mappings = data.get("mappings", [])
    total = data.get("totalCount", 0)

    if total == 0:
        console.print("[yellow]No routes to convert.[/yellow]")
        return

    console.print(f"\n[bold]Spring @RequestMapping Equivalents ({total} routes)[/bold]\n")

    for mapping in mappings:
        route = mapping.get("ratpackRoute", {})
        method = route.get("method", "ALL")
        style = _method_style(method)

        console.print(f"[{style}]{method}[/{style}] [cyan]{route.get('pathPattern', '')}[/cyan]")
        console.print(f"  Annotation: [green]{mapping.get('springAnnotation', '')}[/green]")
        console.print(f"  Signature:  [dim]{mapping.get('methodSignature', '')}[/dim]")

        notes = mapping.get("notes", [])
        if notes:
            for note in notes:
                console.print(f"  [yellow]Note: {note}[/yellow]")

        console.print()


@app.command(name="list")
def list_routes(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    method: Optional[str] = typer.Option(
        None,
        "--method",
        "-m",
        help="Filter by HTTP method (GET, POST, PUT, PATCH, DELETE)",
    ),
    include_libraries: bool = typer.Option(
        False, "--include-libraries", "-L", help="Include library classes"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """List all routes in the application."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_routes(include_libraries=include_libraries)

        # Apply method filter client-side
        if method and not json_output:
            summary = result.get("summary", {})
            routes = summary.get("routes", [])
            filtered = [r for r in routes if r.get("method", "").upper() == method.upper()]
            result["summary"]["routes"] = filtered

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_routes_summary(result)


@app.command(name="tree")
def route_tree(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    include_libraries: bool = typer.Option(
        False, "--include-libraries", "-L", help="Include library classes"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show routes as a tree structure."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_route_tree(include_libraries=include_libraries)

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_route_tree(result)


@app.command(name="spring")
def spring_mappings(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    include_libraries: bool = typer.Option(
        False, "--include-libraries", "-L", help="Include library classes"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Generate Spring @RequestMapping equivalents for routes."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_spring_mappings(include_libraries=include_libraries)

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_spring_mappings(result)
