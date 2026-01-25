"""Error codes and exceptions for CodeLens CLI."""

from enum import IntEnum


class ExitCode(IntEnum):
    """Standard exit codes for the CLI."""

    SUCCESS = 0
    GENERAL_ERROR = 1
    INVALID_USAGE = 2
    PROJECT_NOT_FOUND = 3
    SERVER_ERROR = 4
    TIMEOUT = 5
    CONNECTION_ERROR = 6
    NOT_RUNNING = 7


class CodeLensError(Exception):
    """Base exception for CodeLens CLI errors."""

    def __init__(
        self, message: str, exit_code: ExitCode = ExitCode.GENERAL_ERROR
    ) -> None:
        super().__init__(message)
        self.exit_code = exit_code


class ProjectNotFoundError(CodeLensError):
    """Raised when the project directory is not found or invalid."""

    def __init__(self, message: str) -> None:
        super().__init__(message, ExitCode.PROJECT_NOT_FOUND)


class ServerError(CodeLensError):
    """Raised when there's an error with the server."""

    def __init__(self, message: str) -> None:
        super().__init__(message, ExitCode.SERVER_ERROR)


class TimeoutError(CodeLensError):
    """Raised when an operation times out."""

    def __init__(self, message: str) -> None:
        super().__init__(message, ExitCode.TIMEOUT)


class ConnectionError(CodeLensError):
    """Raised when unable to connect to the server."""

    def __init__(self, message: str) -> None:
        super().__init__(message, ExitCode.CONNECTION_ERROR)
