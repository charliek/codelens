"""Characterization tests for the HTTP client.

Uses ``httpx.MockTransport`` to capture every outgoing request and assert
on path, query parameters, body, and FQN encoding. These tests document
the API surface as the Python CLI consumes it -- a future Go port can
reuse this exact shape against the unchanged server.
"""

from __future__ import annotations

import json
from typing import Any
from urllib.parse import parse_qs, urlparse

import httpx
import pytest

from codelens_cli.client import CodeLensClient


class _Capture:
    """Per-test container that holds the most recent captured request."""

    def __init__(self) -> None:
        self.requests: list[httpx.Request] = []

    @property
    def last(self) -> httpx.Request:
        assert self.requests, "no request captured"
        return self.requests[-1]

    @property
    def last_path(self) -> str:
        return urlparse(str(self.last.url)).path

    @property
    def last_query(self) -> dict[str, list[str]]:
        return parse_qs(urlparse(str(self.last.url)).query, keep_blank_values=True)

    def last_body(self) -> Any:
        body = self.last.content
        if not body:
            return None
        return json.loads(body)


@pytest.fixture
def client_with_capture() -> tuple[CodeLensClient, _Capture]:
    """Build a CodeLensClient whose underlying httpx.Client uses MockTransport."""
    capture = _Capture()

    def handler(request: httpx.Request) -> httpx.Response:
        capture.requests.append(request)
        # Return a permissive default response. Individual tests that need a
        # specific body can patch this in-test.
        return httpx.Response(200, json={"ok": True})

    transport = httpx.MockTransport(handler)
    # Build the client and swap its underlying httpx.Client for one wired to
    # the mock transport.
    client = CodeLensClient(host="localhost", port=8080)
    client._client.close()
    client._client = httpx.Client(base_url=client.base_url, transport=transport)
    yield client, capture
    client.close()


# ============================== plain endpoints ==============================


class TestAdminEndpoints:
    def test_health(self, client_with_capture: tuple[CodeLensClient, _Capture]) -> None:
        client, capture = client_with_capture
        client.health()
        assert capture.last.method == "GET"
        assert capture.last_path == "/admin/health"

    def test_ready(self, client_with_capture: tuple[CodeLensClient, _Capture]) -> None:
        client, capture = client_with_capture
        client.ready()
        assert capture.last_path == "/admin/ready"

    def test_info(self, client_with_capture: tuple[CodeLensClient, _Capture]) -> None:
        client, capture = client_with_capture
        client.info()
        assert capture.last_path == "/admin/info"

    def test_project(self, client_with_capture: tuple[CodeLensClient, _Capture]) -> None:
        client, capture = client_with_capture
        client.project()
        assert capture.last_path == "/api/v1/project"

    def test_refresh_is_post(self, client_with_capture: tuple[CodeLensClient, _Capture]) -> None:
        client, capture = client_with_capture
        client.refresh()
        assert capture.last.method == "POST"
        assert capture.last_path == "/api/v1/project/refresh"

    def test_stats(self, client_with_capture: tuple[CodeLensClient, _Capture]) -> None:
        client, capture = client_with_capture
        client.stats()
        assert capture.last_path == "/api/v1/stats"


# ============================== classes ==============================


class TestClasses:
    def test_list_classes_pagination_defaults(self, client_with_capture: tuple[CodeLensClient, _Capture]) -> None:
        client, capture = client_with_capture
        client.list_classes()
        assert capture.last_path == "/api/v1/classes"
        q = capture.last_query
        assert q["page"] == ["0"]
        assert q["size"] == ["50"]

    def test_list_classes_filters_map_to_documented_query_param_names(
        self, client_with_capture: tuple[CodeLensClient, _Capture]
    ) -> None:
        client, capture = client_with_capture
        client.list_classes(
            package="com.example.*",
            name="*Handler",
            annotation="javax.inject.Singleton",
            extends="com.example.BaseHandler",
            implements="ratpack.handling.Handler",
            interfaces_only=True,
            include_libraries=True,
            page=2,
            size=25,
        )
        q = capture.last_query
        assert q["package"] == ["com.example.*"]
        assert q["name"] == ["*Handler"]
        assert q["annotation"] == ["javax.inject.Singleton"]
        assert q["extends"] == ["com.example.BaseHandler"]
        assert q["implements"] == ["ratpack.handling.Handler"]
        # Booleans are serialized as the lowercase string "true" (locked
        # contract: server reads case-insensitively but a Go port should
        # produce the same wire form).
        assert q["interfaces"] == ["true"]
        assert q["includeLibraries"] == ["true"]
        assert q["page"] == ["2"]
        assert q["size"] == ["25"]

    def test_get_class_url_encodes_fqn(self, client_with_capture: tuple[CodeLensClient, _Capture]) -> None:
        client, capture = client_with_capture
        client.get_class("com.example.Outer$Inner")
        # `$` MUST be percent-encoded as %24 so the server's route segment
        # parser sees it as one path component.
        assert "%24" in str(capture.last.url)
        # Decoded the URL should resolve to the right path component.
        assert "com.example.Outer$Inner" in capture.last_path.replace("%24", "$").replace("/api/v1/classes/", "")

    def test_get_class_dotted_fqn_stays_in_one_segment(
        self, client_with_capture: tuple[CodeLensClient, _Capture]
    ) -> None:
        client, capture = client_with_capture
        client.get_class("com.example.UserHandler")
        # `quote(..., safe="")` percent-encodes dots so the server sees the
        # FQN as a single path component.
        url = str(capture.last.url)
        assert "%2E" in url or "." in url  # current behavior: dots are passed through
        # Either way, the path must START with /api/v1/classes/ and have no further /.
        path = capture.last_path
        suffix = path[len("/api/v1/classes/") :]
        assert "/" not in suffix, f"FQN must be a single path segment, got {path!r}"

    def test_get_implementations_only_sends_param_when_true(
        self, client_with_capture: tuple[CodeLensClient, _Capture]
    ) -> None:
        client, capture = client_with_capture
        client.get_implementations("ratpack.handling.Handler", include_libraries=False)
        assert capture.last_query == {}

        client.get_implementations("ratpack.handling.Handler", include_libraries=True)
        assert capture.last_query == {"includeLibraries": ["true"]}

    def test_get_hierarchy(self, client_with_capture: tuple[CodeLensClient, _Capture]) -> None:
        client, capture = client_with_capture
        client.get_hierarchy("com.example.UserHandler")
        assert capture.last_path.startswith("/api/v1/hierarchy/")

    def test_get_dependencies(self, client_with_capture: tuple[CodeLensClient, _Capture]) -> None:
        client, capture = client_with_capture
        client.get_dependencies("com.example.UserHandler")
        assert capture.last_path.startswith("/api/v1/dependencies/")

    def test_get_annotation_usages(self, client_with_capture: tuple[CodeLensClient, _Capture]) -> None:
        client, capture = client_with_capture
        client.get_annotation_usages("javax.inject.Singleton")
        assert capture.last_path.startswith("/api/v1/annotations/usages/")


# ============================== methods ==============================


class TestMethods:
    def test_search_methods_pagination_defaults(
        self, client_with_capture: tuple[CodeLensClient, _Capture]
    ) -> None:
        client, capture = client_with_capture
        client.search_methods()
        assert capture.last_path == "/api/v1/methods"
        q = capture.last_query
        assert q["page"] == ["0"]
        assert q["size"] == ["50"]

    def test_search_methods_filters_use_camelCase_query_params(
        self, client_with_capture: tuple[CodeLensClient, _Capture]
    ) -> None:
        client, capture = client_with_capture
        client.search_methods(
            name="get*",
            return_type="ratpack.exec.Promise",
            annotation="javax.inject.Inject",
            in_class="com.example.UserHandler",
            in_package="com.example.*",
            include_libraries=True,
        )
        q = capture.last_query
        # camelCase wire names locked here.
        assert q["name"] == ["get*"]
        assert q["returnType"] == ["ratpack.exec.Promise"]
        assert q["annotation"] == ["javax.inject.Inject"]
        assert q["inClass"] == ["com.example.UserHandler"]
        assert q["inPackage"] == ["com.example.*"]
        assert q["includeLibraries"] == ["true"]


# ============================== ktlint (POST + typed Pydantic) ==============================


class TestKtlint:
    def test_lint_file_posts_json_body_with_filePath(
        self, client_with_capture: tuple[CodeLensClient, _Capture]
    ) -> None:
        client, capture = client_with_capture

        def handler(request: httpx.Request) -> httpx.Response:
            capture.requests.append(request)
            return httpx.Response(
                200,
                json={
                    "filePath": "/tmp/src/Foo.kt",
                    "errors": [],
                    "errorCount": 0,
                    "durationMs": 12,
                },
            )

        client._client.close()
        client._client = httpx.Client(base_url=client.base_url, transport=httpx.MockTransport(handler))

        client.lint_file("/tmp/src/Foo.kt")
        assert capture.last.method == "POST"
        assert capture.last_path == "/api/v1/ktlint/lint/file"
        assert capture.last_body() == {"filePath": "/tmp/src/Foo.kt"}

    def test_lint_project_omits_pattern_when_not_supplied(
        self, client_with_capture: tuple[CodeLensClient, _Capture]
    ) -> None:
        client, capture = client_with_capture

        def handler(request: httpx.Request) -> httpx.Response:
            capture.requests.append(request)
            return httpx.Response(
                200,
                json={
                    "projectPath": "/tmp",
                    "fileResults": [],
                    "filesScanned": 0,
                    "filesWithErrors": 0,
                    "totalErrorCount": 0,
                    "durationMs": 1,
                },
            )

        client._client.close()
        client._client = httpx.Client(base_url=client.base_url, transport=httpx.MockTransport(handler))

        client.lint_project(include_tests=False)
        assert capture.last_path == "/api/v1/ktlint/lint/project"
        assert capture.last_body() == {"includeTests": False}

    def test_format_file_includes_writeToFile(
        self, client_with_capture: tuple[CodeLensClient, _Capture]
    ) -> None:
        client, capture = client_with_capture

        def handler(request: httpx.Request) -> httpx.Response:
            capture.requests.append(request)
            return httpx.Response(
                200,
                json={
                    "filePath": "/tmp/x.kt",
                    "formattedContent": None,
                    "hasChanges": False,
                    "remainingErrors": [],
                    "durationMs": 3,
                },
            )

        client._client.close()
        client._client = httpx.Client(base_url=client.base_url, transport=httpx.MockTransport(handler))

        client.format_file("/tmp/x.kt", write_to_file=True)
        assert capture.last_path == "/api/v1/ktlint/format/file"
        assert capture.last_body() == {"filePath": "/tmp/x.kt", "writeToFile": True}


# ============================== Ratpack ==============================


class TestRatpackEndpoints:
    def test_list_handlers_no_filters(self, client_with_capture: tuple[CodeLensClient, _Capture]) -> None:
        client, capture = client_with_capture
        client.list_handlers()
        assert capture.last_path == "/api/v1/ratpack/handlers"
        assert capture.last_query == {}

    def test_list_handlers_with_filters(self, client_with_capture: tuple[CodeLensClient, _Capture]) -> None:
        client, capture = client_with_capture
        client.list_handlers(handler_type="GET", tier="HIGH", include_libraries=True)
        q = capture.last_query
        # Note: param is "type" not "handlerType" -- locked.
        assert q["type"] == ["GET"]
        assert q["tier"] == ["HIGH"]
        assert q["includeLibraries"] == ["true"]

    def test_list_integrations_filter_keys(self, client_with_capture: tuple[CodeLensClient, _Capture]) -> None:
        client, capture = client_with_capture
        client.list_integrations(integration_type="HTTP_CLIENT", sub_type="RATPACK_HTTP_CLIENT")
        q = capture.last_query
        # camelCase subType locked.
        assert q["type"] == ["HTTP_CLIENT"]
        assert q["subType"] == ["RATPACK_HTTP_CLIENT"]

    def test_get_complexity_per_class(self, client_with_capture: tuple[CodeLensClient, _Capture]) -> None:
        client, capture = client_with_capture
        client.get_complexity("com.example.UserHandler")
        assert capture.last_path.startswith("/api/v1/ratpack/complexity/")

    def test_get_migration_order(self, client_with_capture: tuple[CodeLensClient, _Capture]) -> None:
        client, capture = client_with_capture
        client.get_migration_order()
        assert capture.last_path == "/api/v1/ratpack/migration-order"


# ============================== Source ==============================


class TestSource:
    def test_get_source(self, client_with_capture: tuple[CodeLensClient, _Capture]) -> None:
        client, capture = client_with_capture
        client.get_source("com.example.UserHandler")
        assert capture.last_path.startswith("/api/v1/source/")

    def test_get_method_source_with_params_and_context(
        self, client_with_capture: tuple[CodeLensClient, _Capture]
    ) -> None:
        client, capture = client_with_capture
        client.get_method_source(
            "com.example.UserHandler",
            "handle",
            param_types=["ratpack.handling.Context", "java.lang.String"],
            context=3,
        )
        # path: /api/v1/source/{fqn}/method/{name}
        path = capture.last_path
        assert "/method/" in path
        assert path.startswith("/api/v1/source/")
        q = capture.last_query
        # `paramTypes` is comma-joined, `context` is integer.
        assert q["paramTypes"] == ["ratpack.handling.Context,java.lang.String"]
        assert q["context"] == ["3"]
