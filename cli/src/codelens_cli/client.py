"""HTTP client for CodeLens server API."""

from typing import Any

import httpx


class CodeLensClient:
    """Client for communicating with a CodeLens server."""

    def __init__(self, host: str, port: int, timeout: float = 30.0):
        self.base_url = f"http://{host}:{port}"
        self.timeout = timeout

    def _get(self, path: str) -> dict[str, Any]:
        """Make a GET request."""
        with httpx.Client(timeout=self.timeout) as client:
            response = client.get(f"{self.base_url}{path}")
            response.raise_for_status()
            return response.json()

    def _post(self, path: str, data: dict | None = None) -> dict[str, Any]:
        """Make a POST request."""
        with httpx.Client(timeout=self.timeout) as client:
            response = client.post(f"{self.base_url}{path}", json=data)
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
        params = []
        if package:
            params.append(f"package={package}")
        if name:
            params.append(f"name={name}")
        if annotation:
            params.append(f"annotation={annotation}")
        if extends:
            params.append(f"extends={extends}")
        if implements:
            params.append(f"implements={implements}")
        if interfaces_only:
            params.append("interfaces=true")
        if include_libraries:
            params.append("includeLibraries=true")
        params.append(f"page={page}")
        params.append(f"size={size}")

        query = "&".join(params)
        return self._get(f"/api/v1/classes?{query}")

    def get_class(self, fqn: str) -> dict[str, Any]:
        """Get full details for a specific class."""
        return self._get(f"/api/v1/classes/{fqn}")

    def get_implementations(
        self, fqn: str, include_libraries: bool = False
    ) -> dict[str, Any]:
        """Get implementations of an interface or subclasses of a class."""
        params = []
        if include_libraries:
            params.append("includeLibraries=true")
        query = f"?{'&'.join(params)}" if params else ""
        return self._get(f"/api/v1/implementations/{fqn}{query}")

    def get_hierarchy(self, fqn: str) -> dict[str, Any]:
        """Get the class hierarchy for a class."""
        return self._get(f"/api/v1/hierarchy/{fqn}")

    def get_dependencies(
        self, fqn: str, include_libraries: bool = False
    ) -> dict[str, Any]:
        """Get dependencies for a class."""
        params = []
        if include_libraries:
            params.append("includeLibraries=true")
        query = f"?{'&'.join(params)}" if params else ""
        return self._get(f"/api/v1/dependencies/{fqn}{query}")

    def get_annotation_usages(
        self, fqn: str, include_libraries: bool = False
    ) -> dict[str, Any]:
        """Get classes using a specific annotation."""
        params = []
        if include_libraries:
            params.append("includeLibraries=true")
        query = f"?{'&'.join(params)}" if params else ""
        return self._get(f"/api/v1/annotations/usages/{fqn}{query}")

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
        params = []
        if name:
            params.append(f"name={name}")
        if return_type:
            params.append(f"returnType={return_type}")
        if annotation:
            params.append(f"annotation={annotation}")
        if in_class:
            params.append(f"inClass={in_class}")
        if in_package:
            params.append(f"inPackage={in_package}")
        if include_libraries:
            params.append("includeLibraries=true")
        params.append(f"page={page}")
        params.append(f"size={size}")

        query = "&".join(params)
        return self._get(f"/api/v1/methods?{query}")
