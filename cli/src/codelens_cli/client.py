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
