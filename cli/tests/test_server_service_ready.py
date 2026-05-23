"""Characterization tests for ``ServerService._wait_for_ready``.

Pins the CLI's understanding of the server's stdout startup contract:

  CODELENS_STARTING port=<p> host=<h>
      Informational. Server has bound the HTTP listener but the initial scan
      is still running. CLI must ignore this line for readiness purposes.

  CODELENS_READY port=<p> host=<h> version=<v>
      Initial scan finished successfully. CLI matches this line and writes
      ``status=READY`` to its state file.

  CODELENS_ERROR reason=<r> message="<m>"
      Initial scan failed. CLI must surface ``reason`` and ``message`` and
      tear the partially-started server state down rather than waiting out
      the full startup timeout.

These tests run ``_wait_for_ready`` directly against a fake ``subprocess.Popen``
so they exercise the parser in isolation, no real JVM required.
"""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Optional

import pytest

from codelens_cli.services.server_service import ServerService


class _FakeStdout:
    """Stdout stand-in whose ``readline()`` drains a pre-baked list of lines.

    After the list is exhausted, returns empty strings forever (the same way
    a real pipe behaves when the writer hasn't produced more output yet).
    """

    def __init__(self, lines: list[str]) -> None:
        self._lines = iter(lines)

    def readline(self) -> str:
        try:
            return next(self._lines)
        except StopIteration:
            return ""


class _FakeProcess:
    """Minimal ``subprocess.Popen`` stand-in for the readline loop.

    ``poll`` returns None to indicate "still running"; pass an int to simulate
    a process that has exited with that code.
    """

    def __init__(self, stdout: _FakeStdout, poll_returns: Optional[int] = None) -> None:
        self.stdout = stdout
        self._poll = poll_returns
        self.returncode = poll_returns

    def poll(self) -> Optional[int]:
        return self._poll


def _run(coro):
    """Drive an async coroutine to completion in a sync test."""
    return asyncio.run(coro)


def test_returns_ready_info_when_server_emits_ready_line(tmp_path: Path) -> None:
    """The happy path: STARTING line is ignored, READY line is parsed."""
    process = _FakeProcess(
        _FakeStdout(
            [
                "CODELENS_STARTING port=12345 host=127.0.0.1\n",
                "CODELENS_READY port=12345 host=127.0.0.1 version=0.1.0\n",
            ]
        )
    )
    service = ServerService()
    log_file = tmp_path / "test.log"

    result = _run(service._wait_for_ready(process, timeout=5, log_file=log_file))  # type: ignore[arg-type]

    assert result == {"port": 12345, "host": "127.0.0.1", "version": "0.1.0"}


def test_raises_with_reason_and_message_when_scan_fails(tmp_path: Path) -> None:
    """CODELENS_ERROR is surfaced immediately, not collapsed into a timeout."""
    process = _FakeProcess(
        _FakeStdout(
            [
                "CODELENS_STARTING port=12345 host=127.0.0.1\n",
                'CODELENS_ERROR reason=CLASSPATH_RESOLUTION message="Could not resolve project \':app\'"\n',
            ]
        )
    )
    service = ServerService()
    log_file = tmp_path / "test.log"

    with pytest.raises(RuntimeError) as excinfo:
        _run(service._wait_for_ready(process, timeout=5, log_file=log_file))  # type: ignore[arg-type]

    msg = str(excinfo.value)
    assert "CLASSPATH_RESOLUTION" in msg, msg
    assert "Could not resolve project ':app'" in msg, msg


def test_raises_timeout_when_no_ready_signal_arrives(tmp_path: Path) -> None:
    """If neither READY nor ERROR appears within the timeout, raise TimeoutError."""
    process = _FakeProcess(_FakeStdout([]))  # never emits anything
    service = ServerService()
    log_file = tmp_path / "test.log"

    with pytest.raises(TimeoutError):
        # Use a small timeout so the test stays fast.
        _run(service._wait_for_ready(process, timeout=1, log_file=log_file))  # type: ignore[arg-type]
