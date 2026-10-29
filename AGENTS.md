# Orchard - Agent Context

## Project Overview

Orchard is a cloud development environment (CDE) platform - a competitor to Gitpod/Ona. It provisions VMs with devcontainers for developers, with planned integration into the Moderne ecosystem (code modernization via OpenRewrite).

## Themed Naming Convention

Everything in this project uses orchard/gardening terminology:

| Term | Technical Meaning | Notes |
|------|-------------------|-------|
| **Orchard** | The entire platform | Root project name |
| **Grove** | A workspace | A plot plus the fruit growing on it |
| **Cultivator** | A user/developer | Who tends the groves |
| **Plot** | The substrate a grove is planted in: a VM, or later a container host | `Plot` — sealed: `VmPlot`, `ContainerPlot`; holds one or more fruit |
| **Seedling** | What is planted into a plot | Described by `SeedlingSpec` (plot configuration plus metadata) |
| **Sapling** | A `ROOTED` plot, ready to grow fruit | `PlotState.ROOTED` — the provider has an operational substrate |
| **Fruit** | A devcontainer instance | Running development environment, grown on a plot |
| **Seed** | A devcontainer.json spec | Blueprint for growing fruit |
| **Trowel** | The CLI tool | Hand tool for planting |
| **Canopy** | The web UI (separate repo: `orchard-cde/orchard-ui`) | Next.js / React / MUI app |
| **Nursery** | Substrate provider management | Where plots are acquired and seedlings planted |
| **Grove Provider** | The one substrate abstraction | `GroveProvider` — acquire a substrate, grow/compost fruit on it, reach into it; one impl per substrate |
| **Harvest** | Container/image building | Preparing fruit |
| **Roots** | Persistence layer | Database/storage |
| **Trellis** | The Spring Boot application server | Support structure wiring all modules together |
| **Greenhouse** | Prebuild service | Pre-built images and caching |
| **Vine** | Substrate-agnostic exec/attach seam | A conduit that carries commands into a grove; one impl per substrate (SSH today) |
| **Apiary** | AI assistant integration | BeeKeeper extension point — OpenCode, Claude, Gemini, Codex |
| **Fence** | Authentication subsystem | The boundary; OAuth2/OIDC device flow + token issuance |
| **Gateway** | SSH jumphost relaying to groves | Not yet gardening-named — see #223 |

## Module Structure

```
orchard/
├── core/       # Domain models (records, enums)
├── roots/      # JPA entities, Spring Data repos, Flyway migrations
├── harvest/    # DevcontainerParser - parses .devcontainer/devcontainer.json
├── nursery/    # GroveProvider (the substrate seam), QemuGroveProvider, FruitGrower
├── vine/       # Substrate-agnostic exec abstraction - Vine, CommandRunner, SshVine
├── greenhouse/ # Prebuild service - ImageBuilder, PrebuildScheduler, PrebuildService
├── apiary/     # AI assistant integration - BeeKeeper extension point
├── fence/      # Authentication subsystem - OAuth2/OIDC device flow + token issuance
├── trellis/    # Spring Boot app entry point (port 8080) + REST controllers, services, DTOs
├── gateway/    # SSH gateway - MINA SSHD jumphost relaying SSH to VM plots
└── trowel/     # Picocli CLI application
```

There is no `api/` module. Its controllers, services, and DTOs live under
`trellis/src/main/java/dev/orchard/api/` — the package name survived the module merge, so the
path looks like two modules but is one. `integration-tests/` also exists, holding the e2e
`src/integrationTest` source set; note that `./gradlew build` does NOT compile it.

## Tech Stack

- **Language**: Java 25 (records, virtual threads, pattern matching)
- **Build**: Gradle 8.12 with Kotlin DSL
- **Framework**: Spring Boot 4.1
- **Database**: PostgreSQL with Flyway migrations
- **CLI**: Picocli 4.7.6
- **VM Provider**: QEMU (local), AWS EC2 (GCP and Azure stubbed)

## Key Patterns

### Domain Models (core/)
All domain objects are Java records with factory methods:
- `Grove.plant(cultivatorId, name, repoUrl, branch)`
- `VmPlot.germinate(groveId, providerId, seedlingSpec)` (providers call it via `GroveProvider.reserve`)
- `Fruit.bud(groveId, plotId, seed)`

### State Machines
Each entity has a state enum with gardening-themed states:
- `GroveState`: PREPARING → PLANTING → GROWING → FLOURISHING (or BLIGHTED); stop → DORMANT, start → PLANTING; clear → CLEARING → CLEARED (or ORPHANED)
- `PlotState`: GERMINATING → SPROUTING → ROOTED → WILTING → DORMANT | WITHERED (or BLIGHTED on provisioning failure).
- `FruitState`: BUDDING → RIPENING → RIPE (or ROTTED)

### Async Provisioning
VM and container provisioning is async via `CompletableFuture`. The `GroveService.plantGrove()` returns immediately while provisioning continues in background.

## Running the Project

```bash
# Start PostgreSQL
docker compose up -d postgres

# Run API server
./gradlew :trellis:bootRun

# For the web UI (Canopy), see: https://github.com/orchard-cde/orchard-ui

# Use CLI
java -jar trowel/build/libs/trowel-0.1.0-SNAPSHOT-all.jar status
```

## Verifying a Feature

CI runs `./gradlew build compileIntegrationTestJava`: it compiles the integration tests but never
runs them. Before declaring a feature complete, run both of these and report the results:

```bash
./gradlew build
./gradlew :integration-tests:integrationTest -Porchard.e2e.require-qemu=true
```

The integration suite boots real QEMU VMs and plants this repository (its `.devcontainer`, from
`main`) as the grove fixture; it takes several minutes. `-Porchard.e2e.require-qemu=true` makes a
missing QEMU prerequisite fail instead of silently skipping the VM tests. `prepareQemuBaseImage`
downloads the base image on first run. Prerequisites: `qemu-system-*` and `qemu-img`, `mkisofs` or
`genisoimage`, an `~/.ssh/orchard_ed25519` key pair, and a container runtime for Testcontainers
(with podman, set `DOCKER_HOST` to the podman socket, `TESTCONTAINERS_RYUK_DISABLED=true`, and
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` — LocalStack bind-mounts the socket,
and the path must be the one inside the podman machine, not the host-side `DOCKER_HOST` path).
Every test must pass; any failure is a regression.

## Database

Schema is in `roots/src/main/resources/db/migration/V1__initial_schema.sql`. Main tables:
- `cultivators` - users
- `groves` - workspaces; each grove stores its plot as JSON

## Future Work

- Authentication (OAuth2/OIDC)
- Cloud providers (AWS EC2, GCP Compute)
- Moderne SaaS integration for OpenRewrite recipes
- Prebuilds and image caching
- WebSocket for real-time status updates

## Documentation

For detailed architecture and usage documentation, see [docs/TOC.md](docs/TOC.md).
