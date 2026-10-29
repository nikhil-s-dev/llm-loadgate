# Maintainer notes

## Purpose

A Java CLI for measuring streaming LLM endpoint latency and enforcing service-level gates.

## Scope

Request scheduling, streaming timing, latency distributions, and configurable performance gates.

## Validation

The author reports having run and tested the project. The release package was reviewed for repository structure, but its Maven test suite was **not independently executed** during this packaging pass. CI should be green on the public repository before publishing a versioned release.

On Linux/macOS run `./mvnw clean verify`; on Windows run `mvnw.cmd clean verify`. Review the CI logs and reproduce the documented example locally before tagging.

## Engineering principles

- Prefer small, testable changes to broad rewrites.
- Add a regression test when fixing a bug.
- Report measured results with the workload, environment, sample size, and exact command.
- Clearly label synthetic and mock-server examples.
- Document compatibility only after testing the relevant integration.
- Never commit credentials, proprietary prompts, customer datasets, or production endpoint addresses.

## Release process

1. Run tests and the example workflow.
2. Review dependencies, license notices, and security-sensitive changes.
3. Confirm artifact names and release workflow permissions.
4. Merge to `main` after CI passes.
5. Tag a version and inspect the published artifacts.
