"""HTTP client for CodeLens server API."""

from typing import Any

import httpx

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
        return self._get(f"/api/v1/classes/{fqn}")

    def get_implementations(
        self, fqn: str, include_libraries: bool = False
    ) -> dict[str, Any]:
        """Get implementations of an interface or subclasses of a class."""
        params: dict[str, Any] = {}
        if include_libraries:
            params["includeLibraries"] = "true"
        return self._get(f"/api/v1/implementations/{fqn}", params=params or None)

    def get_hierarchy(self, fqn: str) -> dict[str, Any]:
        """Get the class hierarchy for a class."""
        return self._get(f"/api/v1/hierarchy/{fqn}")

    def get_dependencies(
        self, fqn: str, include_libraries: bool = False
    ) -> dict[str, Any]:
        """Get dependencies for a class."""
        params: dict[str, Any] = {}
        if include_libraries:
            params["includeLibraries"] = "true"
        return self._get(f"/api/v1/dependencies/{fqn}", params=params or None)

    def get_annotation_usages(
        self, fqn: str, include_libraries: bool = False
    ) -> dict[str, Any]:
        """Get classes using a specific annotation."""
        params: dict[str, Any] = {}
        if include_libraries:
            params["includeLibraries"] = "true"
        return self._get(f"/api/v1/annotations/usages/{fqn}", params=params or None)

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
