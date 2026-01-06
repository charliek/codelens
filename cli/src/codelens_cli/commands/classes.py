"""Class analysis commands."""

from typing import Optional

import typer

from codelens_cli.commands.common import ensure_server_running, get_client, handle_api_errors
from codelens_cli.formatters import (
    print_class_detail,
    print_class_list,
    print_dependencies,
    print_hierarchy,
    print_implementations,
    print_stats,
)
from codelens_cli.models import (
    ClassDetailResponse,
    ClassListResponse,
    DependenciesResponse,
    HierarchyResponse,
    ImplementationsResponse,
    ScanStatistics,
)
from codelens_cli.output import is_tty, print_json

app = typer.Typer(
    name="classes",
    help="Analyze and explore classes in the codebase.",
    no_args_is_help=True,
)


@app.command(name="list")
def list_classes(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    package: Optional[str] = typer.Option(
        None, "--package", help="Filter by package pattern (supports * wildcard)"
    ),
    name: Optional[str] = typer.Option(
        None, "--name", help="Filter by class name pattern (supports * wildcard)"
    ),
    annotation: Optional[str] = typer.Option(
        None, "--annotation", help="Filter to classes with this annotation"
    ),
    extends: Optional[str] = typer.Option(
        None, "--extends", help="Filter to classes extending this class"
    ),
    implements: Optional[str] = typer.Option(
        None, "--implements", help="Filter to classes implementing this interface"
    ),
    interfaces_only: bool = typer.Option(
        False, "--interfaces", "-i", help="Only show interfaces"
    ),
    include_libraries: bool = typer.Option(
        False, "--include-libraries", "-L", help="Include library classes"
    ),
    page: int = typer.Option(0, "--page", help="Page number (0-based)"),
    size: int = typer.Option(50, "--size", help="Page size"),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """List classes in the codebase with optional filtering."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.list_classes(
            package=package,
            name=name,
            annotation=annotation,
            extends=extends,
            implements=implements,
            interfaces_only=interfaces_only,
            include_libraries=include_libraries,
            page=page,
            size=size,
        )

        if json_output or not is_tty():
            print_json(result)
        else:
            response = ClassListResponse.model_validate(result)
            print_class_list(response)


@app.command(name="show")
def show_class(
    fqn: str = typer.Argument(help="Fully qualified class name"),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show detailed information about a specific class."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_class(fqn)

        if json_output or not is_tty():
            print_json(result)
        else:
            response = ClassDetailResponse.model_validate(result)
            print_class_detail(response)


@app.command(name="stats")
def show_stats(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show scan statistics for the codebase."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.stats()

        if json_output or not is_tty():
            print_json(result)
        else:
            stats = ScanStatistics.model_validate(result)
            print_stats(stats)


@app.command(name="implementations")
def show_implementations(
    fqn: str = typer.Argument(help="Fully qualified interface or class name"),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    include_libraries: bool = typer.Option(
        False, "--include-libraries", "-L", help="Include library classes"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Find all implementations of an interface or subclasses of a class."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_implementations(fqn, include_libraries)

        if json_output or not is_tty():
            print_json(result)
        else:
            response = ImplementationsResponse.model_validate(result)
            print_implementations(response)


@app.command(name="hierarchy")
def show_hierarchy(
    fqn: str = typer.Argument(help="Fully qualified class name"),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show the class hierarchy for a class."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_hierarchy(fqn)

        if json_output or not is_tty():
            print_json(result)
        else:
            response = HierarchyResponse.model_validate(result)
            print_hierarchy(response)


@app.command(name="dependencies")
def show_dependencies(
    fqn: str = typer.Argument(help="Fully qualified class name"),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    include_libraries: bool = typer.Option(
        False, "--include-libraries", "-L", help="Include library classes"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show dependencies for a class (incoming and outgoing)."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_dependencies(fqn, include_libraries)

        if json_output or not is_tty():
            print_json(result)
        else:
            response = DependenciesResponse.model_validate(result)
            print_dependencies(response)
