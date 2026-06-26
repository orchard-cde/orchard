# Orchard

> Cloud Development Environments - Growing the Future of Code

<div align="center">

[![CI](https://img.shields.io/github/actions/workflow/status/orchard-cde/orchard/ci.yml?branch=main&logo=github&label=CI)](https://github.com/orchard-cde/orchard/actions/workflows/ci.yml)
[![GitHub release](https://img.shields.io/github/v/release/orchard-cde/orchard?logo=github&label=Release)](https://github.com/orchard-cde/orchard/releases)
[![License](https://img.shields.io/github/license/orchard-cde/orchard?logo=apache&label=License)](LICENSE)
[![GitLab mirror](https://img.shields.io/badge/mirror-gitlab.com%2Forchard--cde-FC6D26?logo=gitlab)](https://gitlab.com/orchard-cde/orchard)

</div>

Orchard is a cloud development environment (CDE) platform that provisions ready-to-code workspaces from any Git repository. Think Gitpod, but with an orchard theme.

## Features

- **devcontainer Support**: Full compatibility with the `.devcontainer` specification
- **VM-based Isolation**: Each workspace runs in its own QEMU VM with Docker
- **VS Code Remote SSH**: Connect directly from your local VS Code
- **Web UI (Canopy)**: Manage workspaces from your browser — [separate repo](https://github.com/orchard-cde/orchard-ui)
- **CLI (Trowel)**: Plant and manage groves from the command line

## Quick Start

### Prerequisites

- Java 25+
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

# Start the dev server — runs orchard core (:7778) + the Canopy UI BFF (:7777)
trowel dev-server start        # open http://localhost:7777
trowel dev-server start --no-ui  # core only on :7778 (API only, no UI)
```

`trowel dev-server start` launches **two processes**:

- **orchard-server** (core, API only) on port **7778**
- **orchard-ui-backend** (Canopy UI BFF) on port **7777** — serves the Canopy UI and
  reverse-proxies `/api/**` to core. Open **http://localhost:7777** in your browser.

In dev mode, the UI auto-authenticates as the cultivator configured in your trowel config
(`~/.orchard/config.toml`), so no localStorage setup is needed.

The BFF binary (`orchard-ui-backend`) is downloaded automatically from orchard-ui GitHub
releases. Until a pre-built binary is available for your platform (macOS builds are not
yet published), build it locally from the sibling `orchard-ui/` checkout:

```bash
# In the sibling orchard-ui/ repository
./gradlew :backend:nativeCompile
cp backend/build/native/nativeCompile/orchard-ui-backend ~/.orchard/bin/
```

**Fast UI-development loop** — when actively working on orchard-ui source, run the
Next.js dev server for hot-reload instead:

```bash
# In the sibling orchard-ui/ repository
npm run dev   # Next.js on :3000, talks to the core API at http://localhost:7778
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
│              │   Parsing    │  (QEMU, AWS; soon GCP/Azure)  │
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

- **Java 25** - Modern Java with records, virtual threads, pattern matching
- **Spring Boot 4.1** - Application framework
- **PostgreSQL** - Database with Flyway migrations
- **Picocli** - CLI framework
- **QEMU/KVM** - Local VM provisioning
- **AWS EC2** - Cloud VM provisioning (GCP Compute, Azure VMs planned)
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
  nursery:
    provider: qemu  # qemu, aws, gcp, azure
  qemu:
    base-image-path: /tmp/orchard/images/base.qcow2
    vm-storage-path: ${user.home}/.orchard/data/vms
    # enable-kvm/serial-output default per-platform (KVM on Linux, HVF on macOS)
```

### CLI (`~/.orchard/config.toml`)

```toml
active = "local"

[targets.local]
server = "http://localhost:7778"
cultivator = "<your-uuid>"
```

## Roadmap

- [ ] OAuth2/OIDC authentication
- [x] AWS EC2 cloud provider
- [ ] GCP Compute and Azure VM providers
- [ ] Workspace prebuilds and image caching
- [ ] Real-time status updates via WebSocket
- [ ] VS Code extension for direct integration
- [ ] Multi-container workspace support

## License

Orchard is licensed under the [Apache License, Version 2.0](LICENSE). See the [NOTICE](NOTICE) file for attribution.

## Contributing

Contributions welcome! Please read the [contribution guidelines](CONTRIBUTING.md) before submitting PRs.
