"""Source code retrieval commands."""

from typing import Optional

import typer

from codelens_cli.commands.common import ensure_server_running, get_client, handle_api_errors
from codelens_cli.formatters import print_method_source, print_source
from codelens_cli.models import MethodSourceResponse, SourceResponse
from codelens_cli.output import is_tty, print_json

app = typer.Typer(
    name="source",
    help="Retrieve and view source code for classes and methods.",
    no_args_is_help=True,
)


@app.command(name="show")
def show_source(
    fqn: str = typer.Argument(help="Fully qualified class name"),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show source code for a class.

    This retrieves the full source code for a project class by its fully
    qualified name. Library and JDK classes are not supported.

    Example:
        codelens source show com.example.MyClass
    """
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client.get_source(fqn)

        if json_output or not is_tty():
            print_json(result)
        else:
            response = SourceResponse.model_validate(result)
            print_source(response)


@app.command(name="method")
def show_method_source(
    fqn: str = typer.Argument(help="Fully qualified class name"),
    method: str = typer.Argument(help="Method name"),
    project: Optional[str] = typer.Option(
        None, "--project", "-p", help="Project directory"
    ),
    param_types: Optional[str] = typer.Option(
        None, "--params", help="Comma-separated parameter types for disambiguation"
    ),
    context: int = typer.Option(
        0, "--context", "-C", help="Number of context lines before/after method"
    ),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show source code for a specific method.

    Extracts and displays just the method source code from a class.
    Use --params to disambiguate overloaded methods.

    Examples:
        codelens source method com.example.MyClass handle
        codelens source method com.example.MyClass process --params "String,int"
        codelens source method com.example.MyClass render --context 5
    """
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    # Parse param types if provided
    types_list = None
    if param_types:
        types_list = [t.strip() for t in param_types.split(",")]

    with handle_api_errors():
        result = client.get_method_source(
            fqn=fqn,
            method_name=method,
            param_types=types_list,
            context=context,
        )

        if json_output or not is_tty():
            print_json(result)
        else:
            response = MethodSourceResponse.model_validate(result)
            print_method_source(response)
