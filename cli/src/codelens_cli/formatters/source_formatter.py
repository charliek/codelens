"""Formatters for source code output."""

from rich.console import Console
from rich.panel import Panel
from rich.syntax import Syntax

from codelens_cli.models import (
    MethodSourceInfo,
    SourceInfo,
    SourceLanguage,
    SourceResponse,
    MethodSourceResponse,
)

console = Console()


def _get_lexer(language: SourceLanguage) -> str:
    """Get the syntax highlighting lexer for a language."""
    if language == SourceLanguage.KOTLIN:
        return "kotlin"
    elif language == SourceLanguage.JAVA:
        return "java"
    return "text"


def print_source(response: SourceResponse) -> None:
    """Print source code with syntax highlighting."""
    source = response.source

    # Print header
    console.print(f"\n[bold]{source.fqn}[/bold]")
    console.print(f"[dim]File: {source.file_path}[/dim]")
    console.print(f"[dim]Language: {source.language.value} | Lines: {source.line_count}[/dim]")
    if source.module:
        console.print(f"[dim]Module: {source.module}[/dim]")
    console.print()

    # Print source with syntax highlighting
    lexer = _get_lexer(source.language)
    syntax = Syntax(
        source.content,
        lexer,
        line_numbers=True,
        theme="monokai",
        word_wrap=False,
    )
    console.print(syntax)
    console.print()


def _detect_language_from_content(content: str) -> str:
    """Detect language from source content using keyword patterns.

    Returns 'kotlin' or 'java' based on language-specific patterns.
    """
    # Kotlin-specific patterns that don't appear in Java
    kotlin_patterns = [
        "fun ",      # Kotlin function declaration
        "val ",      # Kotlin val declaration
        "var ",      # Kotlin var declaration (also in Java 10+ but rare)
        ": String",  # Kotlin type annotation style
        ": Int",     # Kotlin type annotation style
        "?.let",     # Kotlin safe call
        "?:",        # Elvis operator
        "companion object",
        "data class",
        "sealed class",
        "override fun",
    ]

    for pattern in kotlin_patterns:
        if pattern in content:
            return "kotlin"

    return "java"


def print_method_source(response: MethodSourceResponse) -> None:
    """Print method source code with syntax highlighting."""
    method = response.method_source

    # Print header
    console.print(f"\n[bold]{method.class_fqn}[/bold]")
    console.print(f"[cyan]{method.signature}[/cyan]")
    console.print(f"[dim]Lines: {method.start_line}-{method.end_line}[/dim]")
    console.print()

    # Print context before if present
    if method.context_before:
        console.print("[dim]... context before ...[/dim]")
        console.print(method.context_before)
        console.print()

    # Detect language from source content
    lexer = _detect_language_from_content(method.content)

    syntax = Syntax(
        method.content,
        lexer,
        line_numbers=True,
        start_line=method.start_line,
        theme="monokai",
        word_wrap=False,
    )
    console.print(syntax)

    # Print context after if present
    if method.context_after:
        console.print()
        console.print("[dim]... context after ...[/dim]")
        console.print(method.context_after)

    console.print()
