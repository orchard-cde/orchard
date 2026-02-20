# Orchard - Claude Code Context

## Project Overview

Orchard is a cloud development environment (CDE) platform - a competitor to Gitpod/Ona. It provisions VMs with devcontainers for developers, with planned integration into the Moderne ecosystem (code modernization via OpenRewrite).

## Themed Naming Convention

Everything in this project uses orchard/gardening terminology:

| Term | Technical Meaning | Notes |
|------|-------------------|-------|
| **Orchard** | The entire platform | Root project name |
| **Grove** | A workspace | VM + container combo |
| **Cultivator** | A user/developer | Who tends the groves |
| **Seedling** | A VM being provisioned | Transitions to "Sapling" when ready |
| **Sapling** | A running VM | Ready state for seedlings |
| **Fruit** | A devcontainer | Running development environment |
| **Seed** | A devcontainer.json spec | Blueprint for growing fruit |
| **Trowel** | The CLI tool | Hand tool for planting |
| **Canopy** | The web UI | "See the forest through the trees" |
| **Nursery** | VM provider management | Where seedlings are grown |
| **Harvest** | Container/image building | Preparing fruit |
| **Roots** | Persistence layer | Database/storage |
| **Trellis** | The Spring Boot application server | Support structure wiring all modules together |
| **Greenhouse** | Prebuild service | Pre-built images and caching |

## Module Structure

```
orchard/
├── core/       # Domain models (records, enums)
├── roots/      # JPA entities, Spring Data repos, Flyway migrations
├── harvest/    # DevcontainerParser - parses .devcontainer/devcontainer.json
├── nursery/    # SeedlingProvider interface, QemuSeedlingProvider, FruitGrower
├── api/        # REST controllers, services, DTOs
├── trellis/    # Spring Boot app entry point (port 8080)
├── trowel/     # Picocli CLI application
└── canopy/     # Vaadin web UI (port 8081)
```

## Tech Stack

- **Language**: Java 21 (records, virtual threads, pattern matching)
- **Build**: Gradle 8.12 with Kotlin DSL
- **Framework**: Spring Boot 3.4.2
- **Database**: PostgreSQL with Flyway migrations
- **UI**: Vaadin 24.6.3 (pure Java web framework)
- **CLI**: Picocli 4.7.6
- **VM Provider**: QEMU (local), extensible to cloud providers

## Key Patterns

### Domain Models (core/)
All domain objects are Java records with factory methods:
- `Grove.plant(cultivatorId, name, repoUrl, branch)`
- `Seedling.germinate(groveId, spec)`
- `Fruit.bud(groveId, seedlingId, seed)`

### State Machines
Each entity has a state enum with gardening-themed states:
- `GroveState`: PREPARING → PLANTING → GROWING → FLOURISHING (or BLIGHTED)
- `SeedlingState`: GERMINATING → SPROUTING → SAPLING (or BLIGHTED)
- `FruitState`: BUDDING → RIPENING → RIPE (or ROTTED)

### Async Provisioning
VM and container provisioning is async via `CompletableFuture`. The `GroveService.plantGrove()` returns immediately while provisioning continues in background.

## Running the Project

```bash
# Start PostgreSQL
docker compose up -d postgres

# Run API server
./gradlew :trellis:bootRun

# Run web UI (separate terminal)
./gradlew :canopy:bootRun

# Use CLI
java -jar trowel/build/libs/trowel-0.1.0-SNAPSHOT-all.jar status
```

## Database

Schema is in `roots/src/main/resources/db/migration/V1__initial_schema.sql`. Main tables:
- `cultivators` - users
- `groves` - workspaces with embedded seedling/fruit state

## Future Work

- Authentication (OAuth2/OIDC)
- Cloud providers (AWS EC2, GCP Compute)
- Moderne SaaS integration for OpenRewrite recipes
- Prebuilds and image caching
- WebSocket for real-time status updates
