"""Dependency injection container for CodeLens CLI."""

from typing import Optional

from codelens_cli.services import ProjectService, ServerService


class ServiceContainer:
    """Factory for service instances.

    Provides a centralized way to access service instances,
    making it easy to swap implementations for testing.
    """

    _server_service: Optional[ServerService] = None
    _project_service: Optional[ProjectService] = None

    @classmethod
    def server_service(cls) -> ServerService:
        """Get or create the ServerService instance."""
        if cls._server_service is None:
            cls._server_service = ServerService()
        return cls._server_service

    @classmethod
    def project_service(cls) -> ProjectService:
        """Get or create the ProjectService instance."""
        if cls._project_service is None:
            cls._project_service = ProjectService(server_service=cls.server_service())
        return cls._project_service

    @classmethod
    def reset(cls) -> None:
        """Reset all service instances.

        Useful for testing to ensure clean state between tests.
        """
        cls._server_service = None
        cls._project_service = None

    @classmethod
    def set_server_service(cls, service: ServerService) -> None:
        """Set a custom ServerService instance.

        Useful for testing with mocks.
        """
        cls._server_service = service
        # Reset project service to pick up new server service
        cls._project_service = None

    @classmethod
    def set_project_service(cls, service: ProjectService) -> None:
        """Set a custom ProjectService instance.

        Useful for testing with mocks.
        """
        cls._project_service = service
