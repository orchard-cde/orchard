# Orchard

> Cloud Development Environments - Growing the Future of Code

Orchard is a cloud development environment (CDE) platform that provisions ready-to-code workspaces from any Git repository. Think Gitpod, but with an orchard theme and designed for integration with [OpenRewrite](https://openrewrite.org).

## Features

- **devcontainer Support**: Full compatibility with the `.devcontainer` specification
- **VM-based Isolation**: Each workspace runs in its own QEMU VM with Docker
- **VS Code Remote SSH**: Connect directly from your local VS Code
- **Web UI (Canopy)**: Manage workspaces from your browser — [separate repo](https://github.com/orchard-cde/orchard-ui)
- **CLI (Trowel)**: Plant and manage groves from the command line

## Quick Start

### Prerequisites

- Java 21+
- Docker & Docker Compose
- QEMU (for local VM provisioning)

### Start the Platform

```bash
# Clone the repository
git clone https://github.com/orchard-cde/orchard.git
cd orchard

# Start PostgreSQL
docker compose up -d postgres

# Build the project
./gradlew build -x test

# Start the dev server — serves the UI (Canopy) and API at http://localhost:8080
trowel dev-server start
```

The dev server bundles a pinned static build of the Canopy UI (orchard-ui) and serves
it alongside the API at a single URL. No separate `npm run dev` process is required to
use the app.

**Fast UI-development loop** — when actively working on orchard-ui, run the Next.js dev
server for hot-reload instead:

```bash
# In the sibling orchard-ui/ repository
npm run dev   # Next.js on :3000, talks to the API at http://localhost:8080
```

### Using the CLI (Trowel)

```bash
# Build the CLI
./gradlew :trowel:fatJar

# Initialize configuration
java -jar trowel/build/libs/trowel-0.1.0-SNAPSHOT-all.jar config init

# Check server status
java -jar trowel/build/libs/trowel-0.1.0-SNAPSHOT-all.jar status

# Plant a grove (workspace)
java -jar trowel/build/libs/trowel-0.1.0-SNAPSHOT-all.jar grove plant https://github.com/user/repo

# List your groves
java -jar trowel/build/libs/trowel-0.1.0-SNAPSHOT-all.jar grove list

# Connect via SSH
java -jar trowel/build/libs/trowel-0.1.0-SNAPSHOT-all.jar grove connect <grove-id>
```

## Download Native Binaries

Pre-built native binaries are available for each [release](https://github.com/orchard-cde/orchard/releases):

| Binary | linux-amd64 | linux-arm64 | macos-arm64 |
|---|---|---|---|
| **orchard-server** | `orchard-server-linux-amd64.tar.gz` | `orchard-server-linux-arm64.tar.gz` | `orchard-server-macos-arm64.tar.gz` |
| **trowel** | `trowel-linux-amd64.tar.gz` | `trowel-linux-arm64.tar.gz` | `trowel-macos-arm64.tar.gz` |

Each tarball contains a single statically-linked executable. Download, extract, and run:

```bash
# Example: download and run trowel on Linux amd64
curl -L https://github.com/orchard-cde/orchard/releases/latest/download/trowel-linux-amd64.tar.gz | tar xz
./trowel --version
```

### macOS Gatekeeper

On macOS, you may need to remove the quarantine attribute before running:

```bash
xattr -d com.apple.quarantine orchard-server
xattr -d com.apple.quarantine trowel
```

### Verify Checksums

Each release includes a `checksums-sha256.txt` file. Verify a download with:

```bash
sha256sum --check checksums-sha256.txt --ignore-missing
```

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                Canopy (UI) — separate repo                  │
│          Next.js / React / MUI (orchard-cde/orchard-ui)     │
├─────────────────────────────────────────────────────────────┤
│                        Trowel (CLI)                         │
│                      Picocli Commands                       │
├─────────────────────────────────────────────────────────────┤
│                     Trellis (REST API)                      │
│                  Spring Boot Controllers                    │
├──────────────┬──────────────┬───────────────────────────────┤
│     API      │   Harvest    │           Nursery             │
│   Services   │  Devcontainer│      VM Provisioning          │
│              │   Parsing    │   (QEMU, future: AWS/GCP)     │
├──────────────┴──────────────┴───────────────────────────────┤
│                     Roots (Persistence)                     │
│              JPA Entities, Spring Data Repos                │
├─────────────────────────────────────────────────────────────┤
│                    Core (Domain Models)                     │
│           Grove, Seedling, Fruit, Seed, Cultivator          │
└─────────────────────────────────────────────────────────────┘
```

## Themed Glossary

We use orchard/gardening terminology throughout the codebase:

| Term | Meaning |
|------|---------|
| **Grove** | A development workspace (VM + container) |
| **Cultivator** | A user who tends groves |
| **Seedling** | A VM being provisioned |
| **Sapling** | A running, ready VM |
| **Fruit** | A running devcontainer |
| **Seed** | A devcontainer.json specification |
| **Trowel** | The CLI tool for planting |
| **Canopy** | The web UI - see the forest through the trees |
| **Nursery** | VM provider management |
| **Harvest** | Building container images |
| **Trellis** | The Spring Boot application server |
| **Greenhouse** | Prebuild service for image caching |

## Project Structure

```
orchard/
├── core/       # Domain models (Java records)
├── roots/      # Persistence layer (JPA, Flyway)
├── harvest/    # Devcontainer spec parsing
├── nursery/    # VM lifecycle management
├── api/        # REST API and services
├── trellis/    # Spring Boot application
└── trowel/     # Command-line interface
```

## Documentation

For detailed documentation including architecture deep-dives and usage guides, see [docs/TOC.md](docs/TOC.md).

## Tech Stack

- **Java 21** - Modern Java with records, virtual threads, pattern matching
- **Spring Boot 3.4** - Application framework
- **PostgreSQL** - Database with Flyway migrations
- **Picocli** - CLI framework
- **QEMU/KVM** - Local VM provisioning
- **Gradle** - Build system with Kotlin DSL

## Configuration

### Trellis (`trellis/src/main/resources/application.yml`)

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/orchard
    username: orchard
    password: orchard

orchard:
  qemu:
    base-image-path: /var/lib/orchard/images/base.qcow2
    vm-storage-path: /var/lib/orchard/vms
    enable-kvm: true
```

### CLI (`~/.orchard/config.properties`)

```properties
server=http://localhost:8080
cultivator=<your-uuid>
```

## Roadmap

- [ ] OAuth2/OIDC authentication
- [ ] Cloud providers (AWS EC2, GCP Compute, Azure VMs)
- [ ] Workspace prebuilds and image caching
- [ ] Real-time status updates via WebSocket
- [ ] VS Code extension for direct integration
- [ ] Multi-container workspace support

## License

Orchard is licensed under the [Apache License, Version 2.0](LICENSE). See the [NOTICE](NOTICE) file for attribution.

## Contributing

Contributions welcome! Please read the [contribution guidelines](CONTRIBUTING.md) before submitting PRs.
