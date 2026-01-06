"""Tests for CodeLens CLI settings module."""

from pathlib import Path

import pytest

from codelens_cli.settings import (
    find_sdkman_java,
    parse_sdkmanrc,
)


class TestParseSdkmanrc:
    """Tests for parse_sdkmanrc function."""

    def test_parse_valid_sdkmanrc(self, tmp_path: Path) -> None:
        """Test parsing a valid .sdkmanrc file."""
        sdkmanrc = tmp_path / ".sdkmanrc"
        sdkmanrc.write_text(
            "# Comment line\n" "java=21.0.9-amzn\n" "gradle=8.5\n"
        )

        result = parse_sdkmanrc(sdkmanrc)

        assert result == {"java": "21.0.9-amzn", "gradle": "8.5"}

    def test_parse_empty_file(self, tmp_path: Path) -> None:
        """Test parsing an empty file."""
        sdkmanrc = tmp_path / ".sdkmanrc"
        sdkmanrc.write_text("")

        result = parse_sdkmanrc(sdkmanrc)

        assert result == {}

    def test_parse_comments_only(self, tmp_path: Path) -> None:
        """Test parsing a file with only comments."""
        sdkmanrc = tmp_path / ".sdkmanrc"
        sdkmanrc.write_text("# Just a comment\n# Another comment\n")

        result = parse_sdkmanrc(sdkmanrc)

        assert result == {}

    def test_parse_nonexistent_file(self, tmp_path: Path) -> None:
        """Test parsing a nonexistent file returns empty dict."""
        sdkmanrc = tmp_path / ".sdkmanrc"

        result = parse_sdkmanrc(sdkmanrc)

        assert result == {}

    def test_parse_with_spaces(self, tmp_path: Path) -> None:
        """Test parsing handles spaces around = sign."""
        sdkmanrc = tmp_path / ".sdkmanrc"
        sdkmanrc.write_text("java = 21.0.9-amzn\n")

        result = parse_sdkmanrc(sdkmanrc)

        assert result == {"java": "21.0.9-amzn"}

    def test_parse_mixed_content(self, tmp_path: Path) -> None:
        """Test parsing a file with comments, blank lines, and key-value pairs."""
        sdkmanrc = tmp_path / ".sdkmanrc"
        sdkmanrc.write_text(
            "# Enable auto-env through the sdkman_auto_env config\n"
            "# Add key=value pairs of SDKs to use below\n"
            "\n"
            "java=21.0.9-amzn\n"
            "\n"
        )

        result = parse_sdkmanrc(sdkmanrc)

        assert result == {"java": "21.0.9-amzn"}


class TestFindSdkmanJava:
    """Tests for find_sdkman_java function."""

    def test_find_exact_match(
        self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """Test finding an exact version match."""
        # Create mock SDKMAN structure
        java_dir = tmp_path / ".sdkman" / "candidates" / "java" / "21.0.9-amzn"
        (java_dir / "bin").mkdir(parents=True)
        (java_dir / "bin" / "java").touch()

        # Mock Path.home() to return our temp path
        monkeypatch.setattr(Path, "home", lambda: tmp_path)

        result = find_sdkman_java("21.0.9-amzn")

        assert result == java_dir

    def test_find_fuzzy_match_by_major_version(
        self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """Test fuzzy matching by major version when exact match not found."""
        # Create mock SDKMAN structure with different patch version
        java_dir = tmp_path / ".sdkman" / "candidates" / "java" / "21.0.10-amzn"
        (java_dir / "bin").mkdir(parents=True)
        (java_dir / "bin" / "java").touch()

        monkeypatch.setattr(Path, "home", lambda: tmp_path)

        result = find_sdkman_java("21.0.9-amzn")

        # Should find the 21.x version
        assert result == java_dir

    def test_find_no_match_different_major_version(
        self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """Test when no matching version is found."""
        # Create mock SDKMAN structure with different major version
        java_dir = tmp_path / ".sdkman" / "candidates" / "java" / "17.0.9-tem"
        (java_dir / "bin").mkdir(parents=True)
        (java_dir / "bin" / "java").touch()

        monkeypatch.setattr(Path, "home", lambda: tmp_path)

        result = find_sdkman_java("21.0.9-amzn")

        # Should not match since major version differs
        assert result is None

    def test_no_sdkman_directory(
        self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """Test when SDKMAN is not installed."""
        monkeypatch.setattr(Path, "home", lambda: tmp_path)

        result = find_sdkman_java("21.0.9-amzn")

        assert result is None

    def test_directory_without_java_binary(
        self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """Test when Java directory exists but has no java binary."""
        # Create mock SDKMAN structure without java binary
        java_dir = tmp_path / ".sdkman" / "candidates" / "java" / "21.0.9-amzn"
        (java_dir / "bin").mkdir(parents=True)
        # Don't create the java binary

        monkeypatch.setattr(Path, "home", lambda: tmp_path)

        result = find_sdkman_java("21.0.9-amzn")

        assert result is None
