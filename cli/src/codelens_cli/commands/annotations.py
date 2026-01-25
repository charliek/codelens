"""Annotation analysis commands."""

from typing import Optional

import typer
from rich.console import Console
from rich.table import Table

from codelens_cli.commands.common import (
    ensure_server_running,
    get_client,
    handle_api_errors,
)
from codelens_cli.models import (
    AnnotationUsagesResponse,
    ClassSummary,
)
from codelens_cli.output import get_source_color, is_tty, print_json

app = typer.Typer(
    name="annotations",
    help="Find and analyze annotation usages in the codebase.",
    no_args_is_help=True,
)

console = Console()


@app.command(name="usages")
def show_usages(
    fqn: str = typer.Argument(help="Fully qualified annotation name"),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    include_libraries: bool = typer.Option(
        False, "--include-libraries", "-L", help="Include library classes"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Find all classes using a specific annotation."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_annotation_usages(fqn, include_libraries)

        if json_output or not is_tty():
            print_json(result)
        else:
            response = AnnotationUsagesResponse.model_validate(result)
            _print_annotation_usages(response)


def _get_type_str(cls: ClassSummary) -> str:
    """Get type string for a class."""
    if cls.is_interface:
        return "[blue]interface[/]"
    if cls.is_annotation:
        return "[magenta]annotation[/]"
    if cls.is_enum:
        return "[yellow]enum[/]"
    if cls.is_abstract:
        return "[dim]abstract[/]"
    return "class"


def _print_annotation_usages(response: AnnotationUsagesResponse) -> None:
    """Print annotation usages in a nice table format."""
    console.print(f"\n[bold]Usages of @{response.annotation_fqn.split('.')[-1]}[/bold]")
    console.print(f"Total: {response.total_count} classes")
    console.print()

    if response.total_count == 0:
        console.print("[yellow]No classes found using this annotation.[/yellow]")
        return

    table = Table(show_header=True, header_style="bold")
    table.add_column("Class", style="cyan")
    table.add_column("Type", justify="center")
    table.add_column("Package")
    table.add_column("Source", justify="center")

    for cls in response.usages:
        type_str = _get_type_str(cls)
        source_color = get_source_color(cls.source.value)
        table.add_row(
            cls.simple_name,
            type_str,
            cls.package_name,
            f"[{source_color}]{cls.source.value}[/]",
        )

    console.print(table)
    console.print()
