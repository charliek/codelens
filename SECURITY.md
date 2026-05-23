# Security Policy

## Reporting a vulnerability

If you believe you have found a security vulnerability in CodeLens, please
report it privately rather than opening a public GitHub issue.

Use GitHub's private vulnerability reporting feature for this repository. If it
is unavailable, contact the maintainers privately before sharing details in a
public issue.

Please include:

- A description of the issue and where it was observed.
- Steps to reproduce, ideally with a minimal example.
- The version (`codelens version`) and platform you were running on.
- Any suggested fix or mitigation, if you have one.

We aim to acknowledge reports within a few business days and to provide a
remediation plan or update within 30 days for confirmed issues.

## Scope

CodeLens runs entirely on the developer's machine and analyzes JVM bytecode of
projects the developer points it at. The server binds to `127.0.0.1` by default
and is not intended to be exposed on a network.

In scope:

- Code execution or memory-safety issues triggered by analyzing a crafted JVM
  project.
- Path-traversal or arbitrary-file-read/write in CLI or server endpoints.
- Authentication or transport issues if the server is intentionally exposed.

Out of scope:

- Issues that require an attacker to already have local code execution on the
  developer's machine.
- Behavior of third-party libraries used by CodeLens; please report those
  upstream.

## Supported versions

Until a 1.0 release, only the latest commit on `main` is supported.
