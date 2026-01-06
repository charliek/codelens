"""Main entry point for the CodeLens CLI."""

import typer
from rich.console import Console

from codelens_cli.commands import annotations, classes, lifecycle, methods, project

app = typer.Typer(
    name="codelens",
    help="Analyze Ratpack codebases for migration planning.",
    no_args_is_help=True,
)
console = Console()

# Register command groups
app.add_typer(lifecycle.app, name="")  # Lifecycle commands at root level
app.add_typer(classes.app, name="classes")  # Classes analysis commands
app.add_typer(annotations.app, name="annotations")  # Annotations analysis commands
app.add_typer(methods.app, name="methods")  # Methods search commands
app.command(name="project")(project.project_info)


@app.command()
def version() -> None:
    """Show version information."""
    from codelens_cli import __version__
    from codelens_cli.services import ProjectService, ServerService

    console.print(f"codelens-cli {__version__}")

    # Try to get server version if running
    try:
        server_service = ServerService()
        project_service = ProjectService(server_service=server_service)
        project_path = project_service.get_project_path(None)
        server = server_service.find_server(project_path)
        if server:
            console.print(
                f"codelens-server {server.version} (running on port {server.port})"
            )
    except Exception:
        pass


if __name__ == "__main__":
    app()
