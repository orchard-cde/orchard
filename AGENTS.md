# AI Agent Guidelines

This document provides guidance for AI agents working in this repository.

## Code Transformations (OpenRewrite)

Prefer OpenRewrite recipes for bulk code transformations — unused import removal,
dependency pin cleanup, static analysis fixes, and API migrations. Run
`./gradlew rewriteRun` before attempting manual changes for these categories of work.

### Common commands

| Command | Purpose |
|---------|---------|
| `./gradlew rewriteRun` | Apply all active recipes and modify files in place |
| `./gradlew rewriteDryRun` | Preview changes without modifying files (report at `build/reports/rewrite/rewrite.patch`) |
| `./gradlew rewriteDiscover` | List all available recipes (active and inactive) |

### CI workflow

Trigger the OpenRewrite workflow to apply recipes in CI and auto-open a PR:

```bash
gh workflow run openrewrite.yml
```

Preview changes as a downloadable artifact without committing:

```bash
gh workflow run openrewrite.yml -f dry_run=true
```

### Active recipes

Defined in `rewrite.yml` and referenced in `build.gradle.kts`:

- **`dev.orchard.DependencyCleanup`** — removes explicit version pins in subproject build files that duplicate what the Spring Boot BOM already manages
- **`dev.orchard.BestPractices`** — Gradle best practices, JUnit 5 best practices, common static analysis, unused import removal, trailing newline enforcement

### Inactive recipes

**`dev.orchard.SpringBootMigration`** is defined in `rewrite.yml` but intentionally excluded from `build.gradle.kts`. It is a placeholder for the Spring Boot 4 migration effort tracked in [issue #10](https://github.com/orchard-cde/orchard/issues/10). Do not activate it without carefully reviewing the Spring Boot 4 migration guide and coordinating the full upgrade.
