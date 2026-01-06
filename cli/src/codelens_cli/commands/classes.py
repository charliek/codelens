"""Class analysis commands."""

import asyncio
from typing import Optional

import typer
from rich.console import Console
from rich.table import Table

from codelens_cli.client import CodeLensClient
from codelens_cli.container import ServiceContainer
from codelens_cli.errors import ExitCode
from codelens_cli.models import (
    ClassDetailResponse,
    ClassListResponse,
    ClassSummary,
    DependenciesResponse,
    HierarchyNode,
    HierarchyResponse,
    ImplementationsResponse,
    ProjectStatus,
    ScanStatistics,
)
from codelens_cli.output import is_tty, print_json

app = typer.Typer(
    name="classes",
    help="Analyze and explore classes in the codebase.",
    no_args_is_help=True,
)

console = Console()
err_console = Console(stderr=True)


def _ensure_server_running(project: Optional[str], json_output: bool):
    """Ensure a server is running for the project."""
    server_service = ServiceContainer.server_service()
    project_service = ServiceContainer.project_service()
    project_path = project_service.get_project_path(project)

    server = server_service.find_server(project_path)
    if server is None or server.status != ProjectStatus.READY:
        if not json_output and is_tty():
            err_console.print(
                f"Starting server for [cyan]{project_path.name}[/cyan]..."
            )
        try:
            server = asyncio.run(server_service.start_server(project_path))
        except Exception as e:
            err_console.print(f"[red]Error:[/red] {e}")
            raise typer.Exit(ExitCode.SERVER_ERROR)

    return server, project_path


def _get_client(server) -> CodeLensClient:
    """Create a client for the server."""
    return CodeLensClient(server.host, server.port)


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
    server, project_path = _ensure_server_running(project, json_output)
    client = _get_client(server)

    try:
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
            _print_class_list(response)

    except Exception as e:
        err_console.print(f"[red]Error:[/red] {e}")
        raise typer.Exit(ExitCode.CONNECTION_ERROR)


@app.command(name="show")
def show_class(
    fqn: str = typer.Argument(help="Fully qualified class name"),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show detailed information about a specific class."""
    server, project_path = _ensure_server_running(project, json_output)
    client = _get_client(server)

    try:
        result = client.get_class(fqn)

        if json_output or not is_tty():
            print_json(result)
        else:
            response = ClassDetailResponse.model_validate(result)
            _print_class_detail(response)

    except Exception as e:
        err_console.print(f"[red]Error:[/red] {e}")
        raise typer.Exit(ExitCode.CONNECTION_ERROR)


@app.command(name="stats")
def show_stats(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show scan statistics for the codebase."""
    server, project_path = _ensure_server_running(project, json_output)
    client = _get_client(server)

    try:
        result = client.stats()

        if json_output or not is_tty():
            print_json(result)
        else:
            stats = ScanStatistics.model_validate(result)
            _print_stats(stats)

    except Exception as e:
        err_console.print(f"[red]Error:[/red] {e}")
        raise typer.Exit(ExitCode.CONNECTION_ERROR)


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
    server, project_path = _ensure_server_running(project, json_output)
    client = _get_client(server)

    try:
        result = client.get_implementations(fqn, include_libraries)

        if json_output or not is_tty():
            print_json(result)
        else:
            response = ImplementationsResponse.model_validate(result)
            _print_implementations(response)

    except Exception as e:
        err_console.print(f"[red]Error:[/red] {e}")
        raise typer.Exit(ExitCode.CONNECTION_ERROR)


@app.command(name="hierarchy")
def show_hierarchy(
    fqn: str = typer.Argument(help="Fully qualified class name"),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show the class hierarchy for a class."""
    server, project_path = _ensure_server_running(project, json_output)
    client = _get_client(server)

    try:
        result = client.get_hierarchy(fqn)

        if json_output or not is_tty():
            print_json(result)
        else:
            response = HierarchyResponse.model_validate(result)
            _print_hierarchy(response)

    except Exception as e:
        err_console.print(f"[red]Error:[/red] {e}")
        raise typer.Exit(ExitCode.CONNECTION_ERROR)


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
    server, project_path = _ensure_server_running(project, json_output)
    client = _get_client(server)

    try:
        result = client.get_dependencies(fqn, include_libraries)

        if json_output or not is_tty():
            print_json(result)
        else:
            response = DependenciesResponse.model_validate(result)
            _print_dependencies(response)

    except Exception as e:
        err_console.print(f"[red]Error:[/red] {e}")
        raise typer.Exit(ExitCode.CONNECTION_ERROR)


def _print_class_list(response: ClassListResponse) -> None:
    """Print class list in a nice table format."""
    if response.total_count == 0:
        console.print("[yellow]No classes found matching the filter.[/yellow]")
        return

    # Show filter summary if any filter was applied
    filter_parts = []
    f = response.applied_filter
    if f.package_pattern:
        filter_parts.append(f"package={f.package_pattern}")
    if f.name_pattern:
        filter_parts.append(f"name={f.name_pattern}")
    if f.has_annotation:
        filter_parts.append(f"annotation={f.has_annotation}")
    if f.extends_class:
        filter_parts.append(f"extends={f.extends_class}")
    if f.implements_interface:
        filter_parts.append(f"implements={f.implements_interface}")

    filter_str = ", ".join(filter_parts) if filter_parts else "none"

    start = response.page * response.page_size + 1
    end = start + len(response.classes) - 1
    console.print(
        f"\n[bold]Classes[/bold] ({start}-{end} of {response.total_count}) | Filter: {filter_str}"
    )
    console.print()

    table = Table(show_header=True, header_style="bold")
    table.add_column("Name", style="cyan")
    table.add_column("Type", justify="center")
    table.add_column("Source", justify="center")
    table.add_column("Methods", justify="right")
    table.add_column("Fields", justify="right")

    for cls in response.classes:
        type_str = _get_type_str(cls)
        source_color = {"PROJECT": "green", "LIBRARY": "yellow", "JDK": "dim"}.get(
            cls.source.value, ""
        )
        table.add_row(
            cls.simple_name,
            type_str,
            f"[{source_color}]{cls.source.value}[/]",
            str(cls.method_count),
            str(cls.field_count),
        )

    console.print(table)

    if response.total_pages > 1:
        console.print(
            f"\nPage {response.page + 1} of {response.total_pages}. "
            f"Use --page to navigate."
        )
    console.print()


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


def _print_class_detail(response: ClassDetailResponse) -> None:
    """Print class details in a nice format."""
    info = response.class_info

    console.print(f"\n[bold cyan]{info.name.fqn}[/bold cyan]")
    console.print()

    # Basic info table
    table = Table(show_header=False, box=None, padding=(0, 2))
    table.add_column("Key", style="dim")
    table.add_column("Value")

    table.add_row("Package:", info.name.package_name)
    table.add_row("Type:", _get_type_str_from_info(info))
    table.add_row("Visibility:", info.visibility)
    table.add_row("Source:", info.source.value)

    if info.superclass:
        table.add_row("Extends:", info.superclass)
    if info.interfaces:
        table.add_row("Implements:", ", ".join(info.interfaces))
    if info.annotations:
        ann_strs = [f"@{a.type.split('.')[-1]}" for a in info.annotations]
        table.add_row("Annotations:", ", ".join(ann_strs))

    console.print(table)

    # Methods
    if info.methods:
        console.print(f"\n[bold]Methods ({len(info.methods)})[/bold]")
        method_table = Table(show_header=True, header_style="bold")
        method_table.add_column("Name")
        method_table.add_column("Visibility")
        method_table.add_column("Return Type")
        method_table.add_column("Parameters")

        for method in info.methods:
            if method.is_synthetic:
                continue
            param_str = ", ".join(
                [f"{p.name}: {p.type.split('.')[-1]}" for p in method.parameters]
            )
            method_table.add_row(
                method.name,
                method.visibility,
                method.return_type.split(".")[-1],
                param_str or "-",
            )

        console.print(method_table)

    # Fields
    if info.fields:
        console.print(f"\n[bold]Fields ({len(info.fields)})[/bold]")
        field_table = Table(show_header=True, header_style="bold")
        field_table.add_column("Name")
        field_table.add_column("Visibility")
        field_table.add_column("Type")

        for field in info.fields:
            field_table.add_row(
                field.name,
                field.visibility,
                field.type.split(".")[-1],
            )

        console.print(field_table)

    console.print()


def _get_type_str_from_info(info) -> str:
    """Get type string from ClassInfo."""
    if info.is_interface:
        return "interface"
    if info.is_annotation:
        return "annotation"
    if info.is_enum:
        return "enum"
    if info.is_abstract:
        return "abstract class"
    return "class"


def _print_stats(stats: ScanStatistics) -> None:
    """Print scan statistics in a nice format."""
    console.print("\n[bold]Scan Statistics[/bold]")
    console.print()

    table = Table(show_header=False, box=None, padding=(0, 2))
    table.add_column("Key", style="dim")
    table.add_column("Value")

    table.add_row("Project Classes:", str(stats.project_class_count))
    table.add_row("  - Interfaces:", str(stats.project_interface_count))
    table.add_row("  - Abstract Classes:", str(stats.project_abstract_class_count))
    table.add_row("  - Enums:", str(stats.project_enum_count))
    table.add_row("  - Annotations:", str(stats.project_annotation_count))
    table.add_row("Project Methods:", str(stats.project_method_count))
    table.add_row("Project Fields:", str(stats.project_field_count))
    table.add_row("")
    table.add_row("Library Classes:", str(stats.library_class_count))
    table.add_row("JDK Classes:", str(stats.jdk_class_count))
    table.add_row("")
    table.add_row("Classpath Entries:", str(stats.classpath_entry_count))
    table.add_row("Resolved By:", stats.classpath_resolved_by)
    table.add_row("Scan Duration:", f"{stats.scan_duration_ms}ms")
    table.add_row("Scanned At:", stats.scanned_at)

    console.print(table)
    console.print()


def _print_implementations(response: ImplementationsResponse) -> None:
    """Print implementations in a nice table format."""
    console.print(f"\n[bold]Implementations of {response.target_class}[/bold]")
    console.print(
        f"Total: {response.total_count} "
        f"({len(response.direct_implementations)} direct, "
        f"{len(response.indirect_implementations)} indirect)"
    )
    console.print()

    if response.total_count == 0:
        console.print("[yellow]No implementations found.[/yellow]")
        return

    table = Table(show_header=True, header_style="bold")
    table.add_column("Class", style="cyan")
    table.add_column("Type", justify="center")
    table.add_column("Direct", justify="center")
    table.add_column("Source", justify="center")

    for cls in response.direct_implementations:
        type_str = _get_type_str(cls)
        source_color = {"PROJECT": "green", "LIBRARY": "yellow", "JDK": "dim"}.get(
            cls.source.value, ""
        )
        table.add_row(
            cls.fqn,
            type_str,
            "[green]Yes[/]",
            f"[{source_color}]{cls.source.value}[/]",
        )

    for cls in response.indirect_implementations:
        type_str = _get_type_str(cls)
        source_color = {"PROJECT": "green", "LIBRARY": "yellow", "JDK": "dim"}.get(
            cls.source.value, ""
        )
        table.add_row(
            cls.fqn,
            type_str,
            "[dim]No[/]",
            f"[{source_color}]{cls.source.value}[/]",
        )

    console.print(table)
    console.print()


def _print_hierarchy(response: HierarchyResponse) -> None:
    """Print class hierarchy in a tree format."""
    console.print(f"\n[bold]Hierarchy for {response.target_class}[/bold]")
    console.print()

    # Build parent chain
    parent_chain = []
    node = response.hierarchy.parent
    while node:
        parent_chain.insert(0, node)
        node = node.parent

    # Print parent chain
    if parent_chain:
        console.print("[dim]Parents:[/dim]")
        indent = "  "
        for i, parent in enumerate(parent_chain):
            prefix = "└── " if i == len(parent_chain) - 1 else "├── "
            source_color = {"PROJECT": "green", "LIBRARY": "yellow", "JDK": "dim"}.get(
                parent.source.value, ""
            )
            node_type = "[blue]interface[/]" if parent.is_interface else "class"
            console.print(
                f"{indent}{prefix}[{source_color}]{parent.class_fqn}[/] ({node_type})"
            )
            indent = indent + ("    " if i == len(parent_chain) - 1 else "│   ")

    # Print current class
    source_color = {"PROJECT": "green", "LIBRARY": "yellow", "JDK": "dim"}.get(
        response.hierarchy.source.value, ""
    )
    node_type = "[blue]interface[/]" if response.hierarchy.is_interface else "class"
    console.print(
        f"\n[bold cyan]{response.hierarchy.class_fqn}[/bold cyan] ({node_type})"
    )

    # Print interfaces
    if response.hierarchy.interfaces:
        console.print("\n[dim]Implements:[/dim]")
        for iface in response.hierarchy.interfaces:
            source_color = {"PROJECT": "green", "LIBRARY": "yellow", "JDK": "dim"}.get(
                iface.source.value, ""
            )
            console.print(f"  • [{source_color}]{iface.class_fqn}[/]")

    # Print children
    if response.hierarchy.children:
        console.print(f"\n[dim]Children ({len(response.hierarchy.children)}):[/dim]")
        _print_hierarchy_children(response.hierarchy.children, "  ")

    console.print()


def _print_hierarchy_children(children: list[HierarchyNode], indent: str) -> None:
    """Recursively print hierarchy children."""
    for i, child in enumerate(children):
        is_last = i == len(children) - 1
        prefix = "└── " if is_last else "├── "
        source_color = {"PROJECT": "green", "LIBRARY": "yellow", "JDK": "dim"}.get(
            child.source.value, ""
        )
        node_type = "[blue]interface[/]" if child.is_interface else "class"
        console.print(
            f"{indent}{prefix}[{source_color}]{child.simple_name}[/] ({node_type})"
        )

        if child.children:
            new_indent = indent + ("    " if is_last else "│   ")
            _print_hierarchy_children(child.children, new_indent)


def _print_dependencies(response: DependenciesResponse) -> None:
    """Print dependencies in a nice format."""
    console.print(f"\n[bold]Dependencies for {response.target_class}[/bold]")
    console.print()

    # Outgoing dependencies
    console.print(
        f"[bold]Outgoing[/bold] (this class depends on {len(response.outgoing)} classes):"
    )
    if response.outgoing:
        table = Table(show_header=True, header_style="bold")
        table.add_column("Class", style="cyan")
        table.add_column("Type", justify="center")
        table.add_column("Location")
        table.add_column("Source", justify="center")

        for dep in response.outgoing:
            source_color = {"PROJECT": "green", "LIBRARY": "yellow", "JDK": "dim"}.get(
                dep.source.value, ""
            )
            table.add_row(
                dep.class_fqn,
                dep.dependency_type.value,
                dep.location or "-",
                f"[{source_color}]{dep.source.value}[/]",
            )

        console.print(table)
    else:
        console.print("[dim]  No outgoing dependencies[/dim]")

    console.print()

    # Incoming dependencies
    console.print(
        f"[bold]Incoming[/bold] ({len(response.incoming)} classes depend on this):"
    )
    if response.incoming:
        table = Table(show_header=True, header_style="bold")
        table.add_column("Class", style="cyan")
        table.add_column("Type", justify="center")
        table.add_column("Location")
        table.add_column("Source", justify="center")

        for dep in response.incoming:
            source_color = {"PROJECT": "green", "LIBRARY": "yellow", "JDK": "dim"}.get(
                dep.source.value, ""
            )
            table.add_row(
                dep.class_fqn,
                dep.dependency_type.value,
                dep.location or "-",
                f"[{source_color}]{dep.source.value}[/]",
            )

        console.print(table)
    else:
        console.print("[dim]  No incoming dependencies[/dim]")

    console.print()
