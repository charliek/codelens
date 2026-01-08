"""HTTP client for CodeLens server API."""

from typing import Any
from urllib.parse import quote

import httpx


def _encode_path_param(value: str) -> str:
    """URL-encode a path parameter to prevent traversal attacks."""
    return quote(value, safe="")

from codelens_cli.models import (
    FormatFileResponse,
    FormatProjectResponse,
    LintFileResponse,
    LintProjectResponse,
)


class CodeLensClient:
    """Client for communicating with a CodeLens server.

    Uses a persistent httpx.Client with connection pooling for better performance.
    """

    def __init__(self, host: str, port: int, timeout: float = 30.0):
        self.base_url = f"http://{host}:{port}"
        self._client = httpx.Client(
            base_url=self.base_url,
            timeout=timeout,
            limits=httpx.Limits(
                max_connections=10,
                max_keepalive_connections=5,
            ),
        )

    def close(self) -> None:
        """Close the underlying HTTP client."""
        self._client.close()

    def __enter__(self) -> "CodeLensClient":
        """Enter context manager."""
        return self

    def __exit__(self, *args: Any) -> None:
        """Exit context manager and close client."""
        self.close()

    def _get(self, path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        """Make a GET request."""
        response = self._client.get(path, params=params)
        response.raise_for_status()
        return response.json()

    def _post(self, path: str, data: dict | None = None) -> dict[str, Any]:
        """Make a POST request."""
        response = self._client.post(path, json=data)
        response.raise_for_status()
        return response.json()

    def health(self) -> dict[str, Any]:
        """Check server health."""
        return self._get("/admin/health")

    def ready(self) -> dict[str, Any]:
        """Check if server is ready."""
        return self._get("/admin/ready")

    def info(self) -> dict[str, Any]:
        """Get server info."""
        return self._get("/admin/info")

    def project(self) -> dict[str, Any]:
        """Get project info."""
        return self._get("/api/v1/project")

    def refresh(self) -> dict[str, Any]:
        """Refresh project scan."""
        return self._post("/api/v1/project/refresh")

    def touch_activity(self) -> None:
        """Touch activity to reset idle timer."""
        try:
            self._post("/admin/activity")
        except Exception:
            pass  # Best effort

    def stats(self) -> dict[str, Any]:
        """Get scan statistics."""
        return self._get("/api/v1/stats")

    def list_classes(
        self,
        package: str | None = None,
        name: str | None = None,
        annotation: str | None = None,
        extends: str | None = None,
        implements: str | None = None,
        interfaces_only: bool = False,
        include_libraries: bool = False,
        page: int = 0,
        size: int = 50,
    ) -> dict[str, Any]:
        """List classes with optional filtering."""
        params: dict[str, Any] = {"page": page, "size": size}
        if package:
            params["package"] = package
        if name:
            params["name"] = name
        if annotation:
            params["annotation"] = annotation
        if extends:
            params["extends"] = extends
        if implements:
            params["implements"] = implements
        if interfaces_only:
            params["interfaces"] = "true"
        if include_libraries:
            params["includeLibraries"] = "true"

        return self._get("/api/v1/classes", params=params)

    def get_class(self, fqn: str) -> dict[str, Any]:
        """Get full details for a specific class."""
        return self._get(f"/api/v1/classes/{_encode_path_param(fqn)}")

    def get_implementations(
        self, fqn: str, include_libraries: bool = False
    ) -> dict[str, Any]:
        """Get implementations of an interface or subclasses of a class."""
        params: dict[str, Any] = {}
        if include_libraries:
            params["includeLibraries"] = "true"
        return self._get(f"/api/v1/implementations/{_encode_path_param(fqn)}", params=params or None)

    def get_hierarchy(self, fqn: str) -> dict[str, Any]:
        """Get the class hierarchy for a class."""
        return self._get(f"/api/v1/hierarchy/{_encode_path_param(fqn)}")

    def get_dependencies(
        self, fqn: str, include_libraries: bool = False
    ) -> dict[str, Any]:
        """Get dependencies for a class."""
        params: dict[str, Any] = {}
        if include_libraries:
            params["includeLibraries"] = "true"
        return self._get(f"/api/v1/dependencies/{_encode_path_param(fqn)}", params=params or None)

    def get_annotation_usages(
        self, fqn: str, include_libraries: bool = False
    ) -> dict[str, Any]:
        """Get classes using a specific annotation."""
        params: dict[str, Any] = {}
        if include_libraries:
            params["includeLibraries"] = "true"
        return self._get(f"/api/v1/annotations/usages/{_encode_path_param(fqn)}", params=params or None)

    def search_methods(
        self,
        name: str | None = None,
        return_type: str | None = None,
        annotation: str | None = None,
        in_class: str | None = None,
        in_package: str | None = None,
        include_libraries: bool = False,
        page: int = 0,
        size: int = 50,
    ) -> dict[str, Any]:
        """Search methods across all classes."""
        params: dict[str, Any] = {"page": page, "size": size}
        if name:
            params["name"] = name
        if return_type:
            params["returnType"] = return_type
        if annotation:
            params["annotation"] = annotation
        if in_class:
            params["inClass"] = in_class
        if in_package:
            params["inPackage"] = in_package
        if include_libraries:
            params["includeLibraries"] = "true"

        return self._get("/api/v1/methods", params=params)

    def lint_file(self, file_path: str) -> LintFileResponse:
        """Lint a single Kotlin file."""
        response = self._post("/api/v1/ktlint/lint/file", {"filePath": file_path})
        return LintFileResponse.model_validate(response)

    def lint_project(
        self,
        pattern: str | None = None,
        include_tests: bool = True,
    ) -> LintProjectResponse:
        """Lint all Kotlin files in the project."""
        data: dict[str, Any] = {"includeTests": include_tests}
        if pattern:
            data["pattern"] = pattern
        response = self._post("/api/v1/ktlint/lint/project", data)
        return LintProjectResponse.model_validate(response)

    def format_file(
        self,
        file_path: str,
        write_to_file: bool = False,
    ) -> FormatFileResponse:
        """Format a single Kotlin file."""
        response = self._post(
            "/api/v1/ktlint/format/file",
            {"filePath": file_path, "writeToFile": write_to_file},
        )
        return FormatFileResponse.model_validate(response)

    def format_project(
        self,
        pattern: str | None = None,
        include_tests: bool = True,
        dry_run: bool = False,
    ) -> FormatProjectResponse:
        """Format all Kotlin files in the project."""
        data: dict[str, Any] = {
            "includeTests": include_tests,
            "dryRun": dry_run,
        }
        if pattern:
            data["pattern"] = pattern
        response = self._post("/api/v1/ktlint/format/project", data)
        return FormatProjectResponse.model_validate(response)

    # =========================================================================
    # Ratpack Analysis API
    # =========================================================================

    def list_handlers(
        self,
        handler_type: str | None = None,
        tier: str | None = None,
        include_libraries: bool = False,
    ) -> dict[str, Any]:
        """List Ratpack handlers."""
        params: dict[str, Any] = {}
        if handler_type:
            params["type"] = handler_type
        if tier:
            params["tier"] = tier
        if include_libraries:
            params["includeLibraries"] = "true"
        return self._get("/api/v1/ratpack/handlers", params=params or None)

    def get_handler(self, fqn: str) -> dict[str, Any]:
        """Get detailed information about a handler."""
        return self._get(f"/api/v1/ratpack/handlers/{_encode_path_param(fqn)}")

    def get_promise_summary(self, include_libraries: bool = False) -> dict[str, Any]:
        """Get project-wide Promise usage summary."""
        params: dict[str, Any] = {}
        if include_libraries:
            params["includeLibraries"] = "true"
        return self._get("/api/v1/ratpack/promises", params=params or None)

    def get_promise_usage(self, fqn: str) -> dict[str, Any]:
        """Get Promise usage for a specific class."""
        return self._get(f"/api/v1/ratpack/promises/{_encode_path_param(fqn)}")

    def search_promises(
        self,
        uses_blocking: bool | None = None,
        uses_async: bool | None = None,
        uses_fork: bool | None = None,
        min_operations: int = 0,
    ) -> dict[str, Any]:
        """Search for classes with specific Promise usage patterns."""
        params: dict[str, Any] = {}
        if uses_blocking is not None:
            params["usesBlocking"] = str(uses_blocking).lower()
        if uses_async is not None:
            params["usesAsync"] = str(uses_async).lower()
        if uses_fork is not None:
            params["usesFork"] = str(uses_fork).lower()
        if min_operations > 0:
            params["minOperations"] = min_operations
        return self._get("/api/v1/ratpack/promises/search", params=params or None)

    def get_complexity_summary(self) -> dict[str, Any]:
        """Get project-wide complexity summary."""
        return self._get("/api/v1/ratpack/complexity")

    def get_complexity(self, fqn: str) -> dict[str, Any]:
        """Get complexity score for a specific class."""
        return self._get(f"/api/v1/ratpack/complexity/{_encode_path_param(fqn)}")

    def get_migration_order(self) -> dict[str, Any]:
        """Get suggested migration order."""
        return self._get("/api/v1/ratpack/migration-order")

    def list_modules(self, include_libraries: bool = False) -> dict[str, Any]:
        """List Guice modules."""
        params: dict[str, Any] = {}
        if include_libraries:
            params["includeLibraries"] = "true"
        return self._get("/api/v1/ratpack/modules", params=params or None)

    def get_module(self, fqn: str) -> dict[str, Any]:
        """Get detailed information about a Guice module."""
        return self._get(f"/api/v1/ratpack/modules/{_encode_path_param(fqn)}")

    def get_bindings(self, fqn: str) -> dict[str, Any]:
        """Find all bindings for a specific type."""
        return self._get(f"/api/v1/ratpack/bindings/{_encode_path_param(fqn)}")

    # =========================================================================
    # Source Code Retrieval API
    # =========================================================================

    def get_source(self, fqn: str) -> dict[str, Any]:
        """Get source code for a class.

        Args:
            fqn: Fully qualified class name

        Returns:
            Dict with source info including file path, language, content, and line count
        """
        return self._get(f"/api/v1/source/{_encode_path_param(fqn)}")

    def get_method_source(
        self,
        fqn: str,
        method_name: str,
        param_types: list[str] | None = None,
        context: int = 0,
    ) -> dict[str, Any]:
        """Get source code for a specific method.

        Args:
            fqn: Fully qualified class name
            method_name: Method name
            param_types: Optional list of parameter types for disambiguation
            context: Number of context lines before/after method

        Returns:
            Dict with method source info including content, start/end lines
        """
        params: dict[str, Any] = {}
        if param_types:
            params["paramTypes"] = ",".join(param_types)
        if context > 0:
            params["context"] = context
        return self._get(f"/api/v1/source/{_encode_path_param(fqn)}/method/{_encode_path_param(method_name)}", params=params or None)

    # =========================================================================
    # Integration Detection API
    # =========================================================================

    def list_integrations(
        self,
        type: str | None = None,
        sub_type: str | None = None,
        include_libraries: bool = False,
    ) -> dict[str, Any]:
        """Get project-wide integration summary or filter by type.

        Args:
            type: Filter by integration type (HTTP_CLIENT, DATABASE, etc.)
            sub_type: Filter by sub-type (RATPACK_HTTP_CLIENT, DYNAMODB, etc.)
            include_libraries: Include library classes

        Returns:
            Integration summary or filtered results
        """
        params: dict[str, Any] = {}
        if type:
            params["type"] = type
        if sub_type:
            params["subType"] = sub_type
        if include_libraries:
            params["includeLibraries"] = "true"
        return self._get("/api/v1/ratpack/integrations", params=params or None)

    def get_class_integrations(self, fqn: str) -> dict[str, Any]:
        """Get integrations for a specific class.

        Args:
            fqn: Fully qualified class name

        Returns:
            Class integrations
        """
        return self._get(f"/api/v1/ratpack/integrations/{_encode_path_param(fqn)}")

    def find_integrations_by_type(
        self,
        type: str,
        sub_type: str | None = None,
        include_libraries: bool = False,
    ) -> dict[str, Any]:
        """Find classes by integration type.

        Args:
            type: Integration type
            sub_type: Optional sub-type filter
            include_libraries: Include library classes

        Returns:
            List of classes with the specified integration
        """
        params: dict[str, Any] = {}
        if sub_type:
            params["subType"] = sub_type
        if include_libraries:
            params["includeLibraries"] = "true"
        return self._get(f"/api/v1/ratpack/integrations/by-type/{_encode_path_param(type)}", params=params or None)
