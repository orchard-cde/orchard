# Orchard

> Cloud Development Environments - Growing the Future of Code

Orchard is a cloud development environment (CDE) platform that provisions ready-to-code workspaces from any Git repository. Think Gitpod, but with an orchard theme and designed for integration with [Moderne](https://moderne.io) and [OpenRewrite](https://openrewrite.org).

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

# Run the API server (port 8080)
./gradlew :trellis:bootRun

# For the Web UI (Canopy), see the separate repo:
# https://github.com/orchard-cde/orchard-ui
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
- [ ] Moderne SaaS integration for OpenRewrite recipes
- [ ] Workspace prebuilds and image caching
- [ ] Real-time status updates via WebSocket
- [ ] VS Code extension for direct integration
- [ ] Multi-container workspace support

## License

TBD

## Contributing

Contributions welcome! Please read the contribution guidelines before submitting PRs.
