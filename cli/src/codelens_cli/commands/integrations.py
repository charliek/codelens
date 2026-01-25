"""External service integration detection commands."""

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
    ClassIntegrationsResponse,
    IntegrationsByTypeResponse,
    IntegrationsResponse,
    IntegrationType,
)
from codelens_cli.output import get_source_color, is_tty, print_json


def validate_integration_type(type_name: str) -> str:
    """Validate and normalize integration type.

    Args:
        type_name: The type name to validate

    Returns:
        The validated uppercase type name

    Raises:
        typer.BadParameter: If the type is not valid
    """
    try:
        IntegrationType(type_name.upper())
        return type_name.upper()
    except ValueError:
        valid = ", ".join(t.value for t in IntegrationType)
        raise typer.BadParameter(f"Invalid type '{type_name}'. Valid types: {valid}")


console = Console()

app = typer.Typer(
    name="integrations",
    help="Detect and analyze external service integrations.",
    no_args_is_help=True,
)


def print_integrations_summary(response: IntegrationsResponse) -> None:
    """Print integration summary."""
    summary = response.summary

    console.print("\n[bold]External Service Integrations[/bold]")
    console.print(
        f"Classes with integrations: {summary.classes_with_integrations} | "
        f"Total usages: {summary.total_usages}"
    )
    console.print()

    # Type breakdown table
    if summary.type_breakdown:
        console.print("[bold]By Type:[/bold]")
        for type_name, count in sorted(summary.type_breakdown.items()):
            console.print(f"  {type_name}: {count}")
        console.print()

    # Detailed integrations table
    if summary.integrations:
        table = Table(show_header=True, header_style="bold")
        table.add_column("Type")
        table.add_column("SubType")
        table.add_column("Primary FQN", style="dim")
        table.add_column("Classes", justify="right")
        table.add_column("Usages", justify="right")

        for integration in summary.integrations:
            table.add_row(
                integration.type.value,
                integration.sub_type.value,
                integration.primary_type_fqn.split(".")[-1],  # Simple name
                str(integration.class_count),
                str(integration.usage_count),
            )

        console.print(table)
    console.print()


def print_class_integrations(response: ClassIntegrationsResponse) -> None:
    """Print integrations for a class."""
    ci = response.class_integrations

    console.print(f"\n[bold]{ci.class_fqn}[/bold]")
    source_color = get_source_color(ci.source.value)
    console.print(f"[{source_color}]{ci.source.value}[/] | Package: {ci.package_name}")
    console.print()

    if not ci.integrations:
        console.print("[yellow]No external service integrations detected.[/yellow]")
        return

    console.print(f"[bold]Integrations ({len(ci.integrations)}):[/bold]")

    table = Table(show_header=True, header_style="bold")
    table.add_column("Location")
    table.add_column("Name", style="cyan")
    table.add_column("Type")
    table.add_column("SubType")
    table.add_column("Context", style="dim")

    for usage in ci.integrations:
        table.add_row(
            usage.location.value,
            usage.name,
            usage.integration_type.value,
            usage.sub_type.value,
            usage.context or "",
        )

    console.print(table)
    console.print()


def print_integrations_by_type(response: IntegrationsByTypeResponse) -> None:
    """Print classes with a specific integration type."""
    console.print(
        f"\n[bold]Classes using {response.type.value}"
        f"{' / ' + response.sub_type.value if response.sub_type else ''}[/bold]"
    )
    console.print(f"Total: {response.total_count} classes")
    console.print()

    if not response.classes:
        console.print("[yellow]No classes found.[/yellow]")
        return

    table = Table(show_header=True, header_style="bold")
    table.add_column("Class", style="cyan")
    table.add_column("Source")
    table.add_column("Usages", justify="right")

    for ci in response.classes:
        source_color = get_source_color(ci.source.value)
        table.add_row(
            ci.simple_name,
            f"[{source_color}]{ci.source.value}[/]",
            str(len(ci.integrations)),
        )

    console.print(table)
    console.print()


@app.command(name="list")
def list_integrations(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    type_filter: Optional[str] = typer.Option(
        None, "--type", "-t", help="Filter by type (HTTP_CLIENT, DATABASE, etc.)"
    ),
    sub_type: Optional[str] = typer.Option(
        None, "--sub-type", help="Filter by sub-type (DYNAMODB, SQS, etc.)"
    ),
    include_libraries: bool = typer.Option(
        False, "--include-libraries", "-L", help="Include library classes"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """List external service integrations in the project.

    Shows a summary of all detected integrations grouped by type.
    Use --type to filter to a specific integration type.

    Example:
        codelens integrations list
        codelens integrations list --type HTTP_CLIENT
        codelens integrations list --type DATABASE --sub-type DYNAMODB
    """
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    # Validate type filter if provided
    validated_type = validate_integration_type(type_filter) if type_filter else None

    with handle_api_errors():
        result = client.list_integrations(
            integration_type=validated_type,
            sub_type=sub_type.upper() if sub_type else None,
            include_libraries=include_libraries,
        )

        if json_output or not is_tty():
            print_json(result)
        else:
            # Determine response type based on whether we filtered by type
            if validated_type is None:
                response = IntegrationsResponse.model_validate(result)
                print_integrations_summary(response)
            else:
                response = IntegrationsByTypeResponse.model_validate(result)
                print_integrations_by_type(response)


@app.command(name="show")
def show_class_integrations(
    fqn: str = typer.Argument(help="Fully qualified class name"),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show integrations for a specific class.

    Example:
        codelens integrations show com.example.MyHandler
    """
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_class_integrations(fqn)

        if json_output or not is_tty():
            print_json(result)
        else:
            response = ClassIntegrationsResponse.model_validate(result)
            print_class_integrations(response)


@app.command(name="find")
def find_by_type(
    type_name: str = typer.Argument(
        help="Integration type (HTTP_CLIENT, DATABASE, etc.)"
    ),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    sub_type: Optional[str] = typer.Option(
        None, "--sub-type", help="Filter by sub-type"
    ),
    include_libraries: bool = typer.Option(
        False, "--include-libraries", "-L", help="Include library classes"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Find classes using a specific integration type.

    Example:
        codelens integrations find HTTP_CLIENT
        codelens integrations find DATABASE --sub-type DYNAMODB
        codelens integrations find MESSAGE_QUEUE --sub-type SQS
    """
    # Validate the type name
    validated_type = validate_integration_type(type_name)

    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.find_integrations_by_type(
            integration_type=validated_type,
            sub_type=sub_type.upper() if sub_type else None,
            include_libraries=include_libraries,
        )

        if json_output or not is_tty():
            print_json(result)
        else:
            response = IntegrationsByTypeResponse.model_validate(result)
            print_integrations_by_type(response)
