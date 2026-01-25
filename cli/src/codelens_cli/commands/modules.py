"""Guice module analysis commands."""

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
    name="modules",
    help="Analyze Guice modules and bindings.",
    no_args_is_help=True,
)
console = Console()


def _print_module_list(data: dict) -> None:
    """Print module list in human-readable format."""
    modules = data.get("modules", [])
    total = data.get("totalCount", len(modules))

    if not modules:
        console.print("[yellow]No Guice modules found.[/yellow]")
        return

    table = Table(title=f"Guice Modules ({total} total)")
    table.add_column("Class", style="cyan")
    table.add_column("Type", style="blue")
    table.add_column("Bindings", justify="right")
    table.add_column("@Provides", justify="right")

    for module in modules:
        table.add_row(
            module.get("simpleName", ""),
            module.get("moduleType", ""),
            str(module.get("bindingCount", 0)),
            str(module.get("providesMethodCount", 0)),
        )

    console.print(table)


def _print_module_detail(data: dict) -> None:
    """Print module detail in human-readable format."""
    module = data.get("module", {})
    if not module:
        console.print("[red]Module not found.[/red]")
        return

    console.print(f"\n[bold cyan]{module.get('fqn', '')}[/bold cyan]")
    console.print(f"  Package: {module.get('packageName', '')}")
    console.print(f"  Type: [blue]{module.get('moduleType', '')}[/blue]")

    if module.get("configType"):
        console.print(f"  Config Type: {module.get('configType')}")

    # Bindings
    bindings = module.get("bindings", [])
    if bindings:
        console.print(f"\n[bold]Bindings ({len(bindings)})[/bold]")
        table = Table()
        table.add_column("Bound Type", style="cyan")
        table.add_column("To Type", style="green")
        table.add_column("Source", style="blue")
        table.add_column("Scope", style="dim")

        for binding in bindings:
            table.add_row(
                binding.get("boundType", "").split(".")[-1],
                (binding.get("toType") or "-").split(".")[-1],
                binding.get("bindingSource", ""),
                binding.get("scope", "-") or "-",
            )
        console.print(table)

    # Provides methods
    provides = module.get("providesMethods", [])
    if provides:
        console.print(f"\n[bold]@Provides Methods ({len(provides)})[/bold]")
        table = Table()
        table.add_column("Method", style="cyan")
        table.add_column("Provides", style="green")
        table.add_column("Scope", style="dim")
        table.add_column("Multi", justify="center")
        table.add_column("Dependencies", style="blue")

        for method in provides:
            multi = ""
            if method.get("intoSet"):
                multi = "[yellow]Set[/yellow]"
            elif method.get("intoMap"):
                multi = "[yellow]Map[/yellow]"

            deps = method.get("dependencies", [])
            deps_str = ", ".join(d.split(".")[-1] for d in deps) if deps else "-"

            table.add_row(
                method.get("methodName", ""),
                method.get("providesType", "").split(".")[-1],
                method.get("scope", "-") or "-",
                multi or "[dim]-[/dim]",
                deps_str,
            )
        console.print(table)

    # Installed modules
    installed = module.get("installedModules", [])
    if installed:
        console.print(f"\n[bold]Installed Modules ({len(installed)})[/bold]")
        for m in installed:
            console.print(f"  - {m}")

    console.print()


def _print_bindings(data: dict) -> None:
    """Print binding search results in human-readable format."""
    type_fqn = data.get("typeFqn", "")
    bindings = data.get("bindings", [])
    total = data.get("totalCount", len(bindings))

    console.print(f"\n[bold]Bindings for {type_fqn}[/bold]")

    if not bindings:
        console.print("[yellow]No bindings found for this type.[/yellow]")
        return

    table = Table(title=f"{total} binding(s) found")
    table.add_column("Module", style="cyan")
    table.add_column("Bound Type", style="green")
    table.add_column("Source", style="blue")
    table.add_column("Scope", style="dim")

    for item in bindings:
        binding = item.get("binding", {})
        table.add_row(
            item.get("moduleFqn", "").split(".")[-1],
            binding.get("boundType", "").split(".")[-1],
            binding.get("bindingSource", ""),
            binding.get("scope", "-") or "-",
        )

    console.print(table)
    console.print()


@app.command(name="list")
def list_modules(
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    include_libraries: bool = typer.Option(
        False, "--include-libraries", "-L", help="Include library modules"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """List Guice modules in the codebase."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.list_modules(include_libraries=include_libraries)

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_module_list(result)


@app.command(name="show")
def show_module(
    fqn: str = typer.Argument(help="Fully qualified module class name"),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show detailed information about a Guice module."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_module(fqn)

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_module_detail(result)


@app.command(name="bindings")
def find_bindings(
    fqn: str = typer.Argument(help="Fully qualified type name to find bindings for"),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Find all bindings for a specific type."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_bindings(fqn)

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_bindings(result)
