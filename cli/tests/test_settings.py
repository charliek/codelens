"""Tests for CodeLens CLI settings module."""

from pathlib import Path

import pytest

from codelens_cli.settings import (
    detect_project_java_version,
    find_sdkman_java,
    get_gradle_version,
    needs_older_java_for_gradle,
    parse_sdkmanrc,
    resolve_project_java_home,
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


class TestDetectProjectJavaVersion:
    """Tests for detect_project_java_version function."""

    def test_detect_from_sdkmanrc(self, tmp_path: Path) -> None:
        """Test detecting Java version from .sdkmanrc file."""
        sdkmanrc = tmp_path / ".sdkmanrc"
        sdkmanrc.write_text("java=11.0.28-tem\n")

        result = detect_project_java_version(tmp_path)

        assert result == "11.0.28-tem"

    def test_detect_from_java_version_file(self, tmp_path: Path) -> None:
        """Test detecting Java version from .java-version file."""
        java_version = tmp_path / ".java-version"
        java_version.write_text("17.0.9-tem\n")

        result = detect_project_java_version(tmp_path)

        assert result == "17.0.9-tem"

    def test_sdkmanrc_takes_priority_over_java_version(self, tmp_path: Path) -> None:
        """Test that .sdkmanrc takes priority over .java-version."""
        (tmp_path / ".sdkmanrc").write_text("java=11.0.28-tem\n")
        (tmp_path / ".java-version").write_text("17.0.9-tem\n")

        result = detect_project_java_version(tmp_path)

        assert result == "11.0.28-tem"

    def test_detect_from_gradle_properties_sdkman_path(self, tmp_path: Path) -> None:
        """Test detecting Java version from gradle.properties with SDKMAN path."""
        gradle_props = tmp_path / "gradle.properties"
        gradle_props.write_text(
            "org.gradle.java.home=/Users/test/.sdkman/candidates/java/11.0.28-tem\n"
        )

        result = detect_project_java_version(tmp_path)

        assert result == "11.0.28-tem"

    def test_no_java_config_returns_none(self, tmp_path: Path) -> None:
        """Test that no Java configuration returns None."""
        result = detect_project_java_version(tmp_path)

        assert result is None


class TestGetGradleVersion:
    """Tests for get_gradle_version function."""

    def test_parse_gradle_wrapper_properties(self, tmp_path: Path) -> None:
        """Test parsing Gradle version from wrapper properties."""
        wrapper_dir = tmp_path / "gradle" / "wrapper"
        wrapper_dir.mkdir(parents=True)
        props = wrapper_dir / "gradle-wrapper.properties"
        props.write_text(
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-7.6.1-bin.zip\n"
        )

        result = get_gradle_version(tmp_path)

        assert result == "7.6.1"

    def test_parse_two_part_version(self, tmp_path: Path) -> None:
        """Test parsing a two-part Gradle version."""
        wrapper_dir = tmp_path / "gradle" / "wrapper"
        wrapper_dir.mkdir(parents=True)
        props = wrapper_dir / "gradle-wrapper.properties"
        props.write_text(
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.5-bin.zip\n"
        )

        result = get_gradle_version(tmp_path)

        assert result == "8.5"

    def test_no_wrapper_returns_none(self, tmp_path: Path) -> None:
        """Test that missing wrapper returns None."""
        result = get_gradle_version(tmp_path)

        assert result is None


class TestNeedsOlderJavaForGradle:
    """Tests for needs_older_java_for_gradle function."""

    def _create_wrapper(self, path: Path, version: str) -> None:
        """Helper to create gradle-wrapper.properties with a version."""
        wrapper_dir = path / "gradle" / "wrapper"
        wrapper_dir.mkdir(parents=True)
        props = wrapper_dir / "gradle-wrapper.properties"
        props.write_text(
            f"distributionUrl=https\\://services.gradle.org/distributions/gradle-{version}-bin.zip\n"
        )

    def test_gradle_8_5_plus_compatible(self, tmp_path: Path) -> None:
        """Test that Gradle 8.5+ is compatible with Java 21."""
        self._create_wrapper(tmp_path, "8.5")

        result = needs_older_java_for_gradle(tmp_path)

        assert result is False

    def test_gradle_8_10_compatible(self, tmp_path: Path) -> None:
        """Test that Gradle 8.10 is compatible with Java 21."""
        self._create_wrapper(tmp_path, "8.10")

        result = needs_older_java_for_gradle(tmp_path)

        assert result is False

    def test_gradle_9_compatible(self, tmp_path: Path) -> None:
        """Test that Gradle 9.x is compatible with Java 21."""
        self._create_wrapper(tmp_path, "9.0")

        result = needs_older_java_for_gradle(tmp_path)

        assert result is False

    def test_gradle_8_4_needs_older_java(self, tmp_path: Path) -> None:
        """Test that Gradle 8.4 needs older Java."""
        self._create_wrapper(tmp_path, "8.4")

        result = needs_older_java_for_gradle(tmp_path)

        assert result is True

    def test_gradle_7_x_needs_older_java(self, tmp_path: Path) -> None:
        """Test that Gradle 7.x needs older Java."""
        self._create_wrapper(tmp_path, "7.6.1")

        result = needs_older_java_for_gradle(tmp_path)

        assert result is True

    def test_gradle_6_x_needs_older_java(self, tmp_path: Path) -> None:
        """Test that Gradle 6.x needs older Java."""
        self._create_wrapper(tmp_path, "6.9")

        result = needs_older_java_for_gradle(tmp_path)

        assert result is True

    def test_no_wrapper_returns_false(self, tmp_path: Path) -> None:
        """Test that missing wrapper returns False (assume compatible)."""
        result = needs_older_java_for_gradle(tmp_path)

        assert result is False


class TestResolveProjectJavaHome:
    """Tests for resolve_project_java_home function."""

    def test_resolve_from_sdkmanrc(
        self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """Test resolving Java home from .sdkmanrc via SDKMAN."""
        # Create project with .sdkmanrc
        project_dir = tmp_path / "project"
        project_dir.mkdir()
        (project_dir / ".sdkmanrc").write_text("java=11.0.28-tem\n")

        # Create mock SDKMAN structure
        java_dir = tmp_path / ".sdkman" / "candidates" / "java" / "11.0.28-tem"
        (java_dir / "bin").mkdir(parents=True)
        (java_dir / "bin" / "java").touch()

        monkeypatch.setattr(Path, "home", lambda: tmp_path)

        result = resolve_project_java_home(project_dir)

        assert result == java_dir

    def test_resolve_from_gradle_properties_path(self, tmp_path: Path) -> None:
        """Test resolving Java home from gradle.properties explicit path."""
        project_dir = tmp_path / "project"
        project_dir.mkdir()

        # Create Java installation
        java_dir = tmp_path / "java" / "jdk11"
        (java_dir / "bin").mkdir(parents=True)
        (java_dir / "bin" / "java").touch()

        # Create gradle.properties pointing to it
        (project_dir / "gradle.properties").write_text(
            f"org.gradle.java.home={java_dir}\n"
        )

        result = resolve_project_java_home(project_dir)

        assert result == java_dir

    def test_no_java_found_returns_none(
        self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """Test that missing Java returns None."""
        project_dir = tmp_path / "project"
        project_dir.mkdir()
        (project_dir / ".sdkmanrc").write_text("java=11.0.28-tem\n")

        # No SDKMAN installation
        monkeypatch.setattr(Path, "home", lambda: tmp_path)

        result = resolve_project_java_home(project_dir)

        assert result is None
