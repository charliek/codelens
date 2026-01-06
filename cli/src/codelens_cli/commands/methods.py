"""Method search commands."""

from typing import Optional

import typer
from rich.console import Console
from rich.table import Table

from codelens_cli.commands.common import ensure_server_running, get_client, handle_api_errors
from codelens_cli.models import MethodSearchResponse
from codelens_cli.output import get_source_color, is_tty, print_json

app = typer.Typer(
    name="methods",
    help="Search and analyze methods in the codebase.",
    no_args_is_help=True,
)

console = Console()


@app.command(name="search")
def search_methods(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    name: Optional[str] = typer.Option(
        None, "--name", "-n", help="Filter by method name pattern (supports * wildcard)"
    ),
    return_type: Optional[str] = typer.Option(
        None, "--return-type", "-r", help="Filter by return type FQN"
    ),
    annotation: Optional[str] = typer.Option(
        None, "--annotation", "-a", help="Filter to methods with this annotation"
    ),
    in_class: Optional[str] = typer.Option(
        None, "--class", "-c", help="Filter by containing class FQN"
    ),
    in_package: Optional[str] = typer.Option(
        None, "--package", help="Filter by containing package pattern"
    ),
    include_libraries: bool = typer.Option(
        False, "--include-libraries", "-L", help="Include library classes"
    ),
    page: int = typer.Option(0, "--page", help="Page number (0-based)"),
    size: int = typer.Option(50, "--size", help="Page size"),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Search methods across all classes."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.search_methods(
            name=name,
            return_type=return_type,
            annotation=annotation,
            in_class=in_class,
            in_package=in_package,
            include_libraries=include_libraries,
            page=page,
            size=size,
        )

        if json_output or not is_tty():
            print_json(result)
        else:
            response = MethodSearchResponse.model_validate(result)
            _print_method_search(response)


def _print_method_search(response: MethodSearchResponse) -> None:
    """Print method search results in a nice table format."""
    if response.total_count == 0:
        console.print("[yellow]No methods found matching the filter.[/yellow]")
        return

    start = response.page * response.page_size + 1
    end = start + len(response.methods) - 1
    console.print(f"\n[bold]Methods[/bold] ({start}-{end} of {response.total_count})")
    console.print()

    table = Table(show_header=True, header_style="bold")
    table.add_column("Method", style="cyan")
    table.add_column("Return Type")
    table.add_column("Class")
    table.add_column("Source", justify="center")

    for result in response.methods:
        method = result.method
        # Build method signature
        params = ", ".join(
            [f"{p.type.split('.')[-1]}" for p in method.parameters]
        )
        signature = f"{method.name}({params})"

        source_color = get_source_color(result.class_source.value)
        table.add_row(
            signature,
            method.return_type.split(".")[-1],
            result.class_simple_name,
            f"[{source_color}]{result.class_source.value}[/]",
        )

    console.print(table)

    if response.total_pages > 1:
        console.print(
            f"\nPage {response.page + 1} of {response.total_pages}. "
            f"Use --page to navigate."
        )
    console.print()
