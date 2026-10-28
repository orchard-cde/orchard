# Orchard Architecture

This document describes the architecture of the Orchard CDE (Cloud Development Environment) platform, focusing on how clients communicate with the backend, how grove providers are managed, and considerations for scalability.

For the themed naming glossary, see [README.md](../../README.md#themed-glossary).

## Module Dependency Graph

```mermaid
graph TD
    trellis[trellis - Spring Boot app<br/>incl. dev.orchard.api REST layer]
    roots[roots - JPA entities & repos]
    nursery[nursery - VM provisioning]
    vine[vine - substrate-agnostic exec abstraction]
    harvest[harvest - devcontainer parsing]
    greenhouse[greenhouse - prebuild service]
    apiary[apiary - Bee provisioning]
    core[core - domain models]
    trowel[trowel - CLI]
    fence[fence - auth subsystem]
    gateway[gateway - SSH jumphost]
    canopy[canopy - web UI<br/>separate repo: orchard-cde/orchard-ui]

    trellis --> core
    trellis --> roots
    trellis --> nursery
    trellis --> vine
    trellis --> harvest
    trellis --> greenhouse
    trellis --> apiary

    roots --> core
    roots --> harvest
    nursery --> core
    nursery --> vine
    vine --> core
    harvest --> core
    greenhouse --> core
    greenhouse --> roots
    greenhouse --> harvest
    apiary --> core
    apiary --> nursery
    apiary --> vine

    trowel --> core
    gateway --> core

    canopy -. "HTTP REST / SSE" .-> trellis
    trowel -. "HTTP REST" .-> trellis
    gateway -. "HTTP" .-> trellis
    gateway -. "HTTP" .-> fence
```

**Key**: Solid arrows are compile-time module dependencies. Dashed arrows are runtime network communication. `api/` is not a separate module — its controllers/services/DTOs live at `trellis/src/main/java/dev/orchard/api/`. `fence` and `gateway` are standalone Spring Boot apps with no compile-time dependency from `trellis`; `gateway` reaches both over HTTP at runtime. Canopy is a separate Next.js application in the [`orchard-cde/orchard-ui`](https://github.com/orchard-cde/orchard-ui) repository.

---

## 1. Client-Server Communication

### 1.1 CLI (Trowel) → Backend

Trowel communicates with the Trellis backend over **HTTP/1.1 REST with JSON** payloads.

**Client implementation**: `trowel/src/main/java/dev/orchard/trowel/client/OrchardClient.java`
- Uses Java's `java.net.http.HttpClient` (no external HTTP library)
- 10-second connect timeout
- JSON serialization via Jackson with `JavaTimeModule`

**Authentication**: Every request includes an `X-Cultivator-Id` header containing the cultivator's UUID. This is the dev-mode authentication mechanism.

**Configuration resolution** (highest priority first):
1. CLI flags (`--server`, `--cultivator`)
2. Environment variables (`ORCHARD_SERVER_URL`, `ORCHARD_CULTIVATOR_ID`)
3. Config file (`~/.orchard/config.properties`)
4. Defaults (`http://localhost:8080`)

**API endpoints consumed by Trowel**:

| Command | HTTP Method | Endpoint | Description |
|---------|-------------|----------|-------------|
| `grove plant` | POST | `/api/groves` | Create a new grove |
| `grove list` | GET | `/api/groves` | List cultivator's groves |
| `grove show` | GET | `/api/groves/{id}` | Get grove details |
| `grove clear` | DELETE | `/api/groves/{id}` | Destroy a grove |
| `status` | GET | `/api/health` | Check server health |

```mermaid
sequenceDiagram
    participant CLI as Trowel CLI
    participant API as Trellis API

    CLI->>API: POST /api/groves (X-Cultivator-Id header)
    API-->>CLI: 201 Created {grove JSON}

    loop Poll for status
        CLI->>API: GET /api/groves/{id}
        API-->>CLI: 200 OK {state: GROWING|FLOURISHING|BLIGHTED}
    end
```

### 1.2 Web UI (Canopy) → Backend

Canopy is a **separate Next.js application** in the [`orchard-cde/orchard-ui`](https://github.com/orchard-cde/orchard-ui) repository, built with Next.js 15, React 19, and Material-UI 7 (TypeScript). It communicates with the Trellis backend over two channels:

**REST API** — Same `/api/*` endpoints as Trowel, for CRUD operations.

**Authentication**: Same `X-Cultivator-Id` header as Trowel in dev mode. Configured via environment variables:
- `NEXT_PUBLIC_API_URL` — Trellis backend URL (defaults to `http://localhost:8080`)
- `NEXT_PUBLIC_CULTIVATOR_ID` — Dev-mode cultivator UUID

**Real-time updates — SSE (Server-Sent Events)**

Canopy uses the browser `EventSource` API via a custom `useGroveEvents()` React hook to receive real-time grove state changes.

Backend implementation in `trellis/src/main/java/dev/orchard/api/controller/GroveEventController.java`:
- **Endpoint**: `GET /api/groves/{groveId}/events` (`text/event-stream`)
- **Event name**: `grove-state-changed`
- **Timeout**: 30 minutes
- Emitters managed via `ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>>`

The `useGroveEvents()` hook auto-reconnects with exponential backoff (max 3 retries).

**Message payload** (`GroveStateMessage`):
```json
{
  "groveId": "uuid",
  "groveName": "my-grove",
  "previousState": "GROWING",
  "newState": "FLOURISHING",
  "changedAt": "2026-02-20T12:00:00Z"
}
```

Messages are broadcast by `GroveEventBroadcaster` which listens for Spring `GroveStateChangedEvent` application events.

> **Note**: The Trellis backend also supports WebSocket/STOMP via `/ws/grove-events` (configured in `WebSocketConfig.java`), but Canopy currently uses SSE exclusively.

```mermaid
sequenceDiagram
    participant UI as Canopy UI (Next.js)
    participant API as Trellis API

    UI->>API: POST /api/groves (create grove)
    API-->>UI: 201 Created {grove}

    UI->>API: GET /api/groves/{groveId}/events (SSE)

    Note over API: Async provisioning proceeds...

    API-->>UI: event: grove-state-changed {state: GROWING}
    API-->>UI: event: grove-state-changed {state: FLOURISHING}

    UI->>API: GET /api/groves/{id} (fetch full details)
    API-->>UI: 200 OK {grove with plot + fruits}
```

### 1.3 Authentication Architecture

The platform supports two authentication modes, controlled by `orchard.security.oauth2.enabled`:

**Development mode** (default, `false`):
- `SecurityConfig.permissiveFilterChain()` allows all requests
- Cultivator identity passed via `X-Cultivator-Id` header
- Cultivator auto-created on first API call

**Production mode** (`true`):
- `SecurityConfig.securedFilterChain()` validates JWT tokens
- `/api/health/**`, `/actuator/**`, `/ws/**` are open; `/api/**` requires authentication
- `CultivatorAuthFilter` extracts identity from JWT claims (`sub`, `email`, `preferred_username`, `name`, `picture`)
- Cultivator auto-created via `CultivatorService.findOrCreateCultivator()`
- Cultivator ID stored as request attribute for downstream controllers

---

## 2. Grove Provider Architecture

### 2.1 GroveProvider Interface

Defined in `nursery/src/main/java/dev/orchard/nursery/GroveProvider.java` — the single substrate
abstraction (issue #86). Every nursery implementation implements it directly; there is no second
provider hierarchy.

```java
public interface GroveProvider {
    ProviderType type();                                            // getProviderId() defaults to type().id()
    boolean isAvailable();                                          // Health check
    PlotSizes sizes();                                              // Machine-size tiers (default catalogue)
    Plot reserve(UUID groveId, SeedlingSpec seedlingSpec);          // Stamp a GERMINATING plot; acquires nothing
    CompletableFuture<Plot> plantSubstrate(Plot draft);             // Acquire substrate
    CompletableFuture<Plot> water(Plot plot);                       // Resume stopped substrate
    CompletableFuture<Plot> dormant(Plot plot);                     // Suspend substrate
    CompletableFuture<Void> uproot(Plot plot);                      // Destroy substrate
    CompletableFuture<Plot> inspect(Plot plot);                     // Check status
    CompletableFuture<Fruit> growFruit(Plot plot, Fruit fruit);
    CompletableFuture<Void> compostFruit(Plot plot, Fruit fruit);
    Vine vine(Plot plot);                                           // How to reach in
    void verifyDevcontainerCli(Plot plot, String expectedVersion);
}
```

All operations return `CompletableFuture` for non-blocking async provisioning.

`AbstractGroveProvider<P extends Plot, L>` is the substrate-agnostic base: the `plantSubstrate`
template (`launch` → `ready`, mapped to `ROOTED` or `BLIGHTED`), `reserve` via `draft`, fruit
forwarding to the shared `FruitGrower`, and `vine`/`verifyDevcontainerCli`. A substrate supplies
`draft`, `launch`, `ready`, `vineFor` and `workspacePath`; the guarantee-bearing methods are `final`,
so container and other non-VM substrates extend the base rather than implementing `GroveProvider`
directly.

`AbstractVmGroveProvider<L>` is the VM layer on top of it: `ready` becomes `awaitRunning` →
`resolveEndpoint` → `awaitReachable`, `vineFor` returns an SSH-backed `Vine`, and the workspace
path is `/workspace`.

### 2.2 ProviderRegistry

`nursery/src/main/java/dev/orchard/nursery/ProviderRegistry.java`:
- Stores providers in a `ConcurrentHashMap<String, GroveProvider>` keyed by provider ID
- Configurable default provider via `orchard.nursery.provider` property
- `require(id)` returns the provider or throws `IllegalStateException("Provider '<id>' is not registered")`; `get(id)` returns an `Optional`; `getDefault()` returns the configured default
- Short name mapping (e.g., `"qemu"` → `"qemu-local"`, `"aws"` → `"aws-ec2"`) handled in `NurseryConfig.resolveProviderId()`

### 2.2.1 Plot persistence and provenance

The grove stores its whole `Plot` as JSON; that is the only record of the plot. `GroveEntity.plot()` and `setPlot` convert through `PlotSerializer`, with an `@type` tag of `vm` or `container`. `authorizedKeys` is a launch-time input and is never persisted, so a reloaded `VmPlot` has none.

- **Dispatch by provenance.** Every operation on an existing grove (teardown, stop/start, bee command runner, reconciler liveness) resolves its provider with `ProviderRegistry.require(plot.providerId())`. A grove therefore keeps using the provider that created its plot even if `orchard.nursery.provider` changes. `getDefault()` is used only where no plot exists yet: `plantGrove` (to size and `reserve` the first plot) and `HealthController`.
- **Unregistered provider.** Reachable only if config changes between restarts. `require` throws `IllegalStateException`; async teardown catches it and ends the grove `ORPHANED`, the reconciler marks the grove `BLIGHTED`, and request paths surface the existing 409. No dedicated exception type exists and `GlobalExceptionHandler` is unchanged; a bare `Optional.orElseThrow()` would map to 404 and is not used.
- **Stale-route guards.** QEMU reuses ports from a fixed range, so a stopped or cleared grove's `host:sshPort` can belong to another cultivator's VM. Two independent guards: (1) a cleared grove's plot is persisted `WITHERED` and a stopped grove's plot `DORMANT`; watering a `DORMANT` plot returns it `ROOTED` with a refreshed endpoint (QEMU allocates a fresh port); (2) `GatewayGroveService.isRoutable` requires grove `FLOURISHING`, a `VmPlot` with non-null `host`, and plot state `ROOTED`. Container plots are never routable. The gateway route carries `host` and `sshPort`.

### 2.3 Available Providers

| Provider | ID | Config Prefix | Conditional On |
|----------|-----|---------------|----------------|
| QEMU (local) | `qemu-local` | `orchard.qemu.*` | Always registered |
| AWS EC2 | `aws-ec2` | `orchard.nursery.aws.*` | `aws.region` is set |
| GCP Compute | `gcp-compute` | `orchard.nursery.gcp.*` | `gcp.project` is set |
| Azure VM | `azure-vm` | `orchard.nursery.azure.*` | `azure.subscription-id` is set |

Cloud providers are conditionally registered via `@ConditionalOnProperty` beans in `trellis/src/main/java/dev/orchard/trellis/config/NurseryConfig.java`.

### 2.4 QEMU Provider Flow

The QEMU provider (`nursery/src/main/java/dev/orchard/nursery/qemu/QemuGroveProvider.java`) provisions local VMs:

```mermaid
sequenceDiagram
    participant GS as GroveService
    participant QP as QemuGroveProvider
    participant FS as Filesystem
    participant VM as QEMU Process

    GS->>QP: plantSubstrate(plot)
    QP->>FS: Create VM directory
    QP->>FS: Create COW disk image (qemu-img create)
    QP->>FS: Generate cloud-init ISO
    QP->>QP: Allocate SSH port (49152-49999)
    QP->>VM: Start QEMU process
    QP->>VM: Wait for SSH port open (TCP probe)
    QP->>VM: Wait for SSH auth ready
    QP-->>GS: Plot (ROOTED, host + port)
```

**Cloud-init provisions the VM with**:
- User `cultivator` with sudo (NOPASSWD)
- SSH public key from `ORCHARD_SSH_PUBLIC_KEY` env or `~/.ssh/orchard_ed25519.pub`
- Packages: `docker.io`, `git`
- `/workspace` directory owned by `cultivator`
- Docker service started, `cultivator` added to `docker` group

---

## 3. Grove Provisioning Flow

### 3.1 End-to-End Sequence

`trellis/src/main/java/dev/orchard/api/service/GroveService.java`:

**Phase 1 — Synchronous (within transaction)**:
1. Ensure cultivator exists
2. Create `Grove` record (state: `PLANTING`)
3. Reserve a plot via `GroveProvider.reserve` (state: `GERMINATING`), sized from the `SeedlingSpec` for the requested tier (small/medium/large) and persisted on the grove
4. Persist to database
5. Register async callback via `TransactionSynchronization.afterCommit()`

**Phase 2 — Asynchronous (after commit)**:

```mermaid
sequenceDiagram
    participant GS as GroveService
    participant PR as ProviderRegistry
    participant SP as GroveProvider
    participant CR as CommandRunner
    participant SSH as SshExecutor
    participant DP as DevcontainerParser
    participant FG as FruitGrower
    participant DB as GroveRepository
    participant EP as EventPublisher

    GS->>PR: require(plot.providerId())
    PR-->>GS: GroveProvider
    GS->>SP: plantSubstrate(plot)
    SP-->>GS: Plot (ROOTED)
    GS->>DB: updateGroveState(GROWING)
    GS->>EP: GroveStateChangedEvent

    Note over GS,CR: GroveService reaches the Grove via GroveProvider.vine(plot).commands()
    GS->>CR: Poll cloud-init status (every 5s, max 60 attempts)
    CR-->>GS: "done"

    GS->>CR: git clone --depth 1 {repoUrl} /workspace
    CR-->>GS: commit SHA

    GS->>CR: Read /workspace/.devcontainer/devcontainer.json
    GS->>DP: parseJson(content)
    DP-->>GS: Seed

    Note over FG,SSH: FruitGrower still constructs SshExecutor directly (see #86)
    alt Single container
        GS->>FG: grow(target, fruit)
        FG->>SSH: docker pull/build + docker run
        FG-->>GS: Fruit (RIPE)
    else Docker Compose
        GS->>SSH: docker compose config --services
        SSH-->>GS: [service1, service2, ...]
        GS->>FG: growCompose(target, fruits, composeFile)
        FG->>SSH: docker compose up -d
        FG-->>GS: List<Fruit> (RIPE)
    end

    GS->>DB: updateGroveState(FLOURISHING)
    GS->>EP: GroveStateChangedEvent
```

### 3.2 State Machines

#### Grove States

```mermaid
stateDiagram-v2
    [*] --> PREPARING
    PREPARING --> PLANTING
    PLANTING --> GROWING : VM provisioned
    GROWING --> FLOURISHING : Containers ready
    GROWING --> BLIGHTED : Provisioning failed

    PLANTING --> BLIGHTED : VM failed

    FLOURISHING --> CLEARING : User requests clear
    CLEARING --> CLEARED : Teardown complete

    FLOURISHING --> DORMANT : stop
    GROWING --> DORMANT : stop
    BLIGHTED --> DORMANT : stop (plot ROOTED)
    DORMANT --> BLIGHTED : stop failed
    DORMANT --> PLANTING : start (plot DORMANT)
    BLIGHTED --> PLANTING : start (plot DORMANT)

    FLOURISHING --> BLIGHTED : Reconciler detects unreachable
```

#### Plot States

```mermaid
stateDiagram-v2
    [*] --> GERMINATING
    GERMINATING --> SPROUTING : VM starting
    SPROUTING --> ROOTED : SSH ready

    ROOTED --> WILTING : dormant
    WILTING --> DORMANT : powered off
    DORMANT --> ROOTED : water
    WILTING --> WITHERED : terminated
    GERMINATING --> BLIGHTED : Failed
    SPROUTING --> BLIGHTED : Failed
```

#### Fruit States

```mermaid
stateDiagram-v2
    [*] --> BUDDING
    BUDDING --> RIPENING : Container starting
    RIPENING --> RIPE : Container healthy

    RIPE --> PICKED : Stopped
    BUDDING --> ROTTED : Failed
    RIPENING --> ROTTED : Failed
```

### 3.3 Grove Teardown

`GroveService.clearGrove()` is the only path that deletes a plot's disk. It ends in `tearDownAndRecord`:

1. The grove is already persisted `CLEARING`. Register the async callback
2. **After commit, Phase 1 (substrate):** resolve the provider with `require(plot.providerId())`
3. Best-effort composting, only if some fruit has a `containerId`: `provider.inspect(plot)` must return `ROOTED`, otherwise (or if `inspect` throws) composting is skipped. Each `compostFruit` failure is logged at WARN and skipped
4. `provider.uproot(plot)`. An unregistered provider or an `uproot` failure keeps every record and sets `ORPHANED`
5. **Phase 2 (bookkeeping):** save `CLEARED` with the plot `WITHERED` in one write, then delete the fruit rows captured when teardown began. Failures in this phase are logged, not escalated to `ORPHANED`, since the substrate is already gone

### 3.4 Grove Stop and Start

Stop powers the plot off and keeps its disk, containers and every record; start brings the same plot and containers back. Neither re-plants, clones, or runs `devcontainer up`. A refused stop or start throws `IllegalStateException`, which `GlobalExceptionHandler` answers with 409 naming the grove and plot states.

| Operation | Allowed when | Synchronous (request transaction) | Async, after commit |
|---|---|---|---|
| `stopGrove` | grove `GROWING`/`FLOURISHING`, or `BLIGHTED` with plot `ROOTED` | grove `DORMANT`; `BeeService.hibernateAll` marks `HATCHING`/`BUZZING`/`POLLINATING` bees `HIBERNATING` | `pickFruit` each fruit with a container → fruits `PICKED`; `dormant(plot)` → plot `DORMANT` |
| `startGrove` | plot `DORMANT` and grove `DORMANT` or `BLIGHTED` | grove `PLANTING` | `water(plot)` → plot `ROOTED`, grove `GROWING`; `ripenFruit` each fruit with a container → fruits `RIPE`, grove `FLOURISHING` |

Start does not wake bees; the cultivator wakes each with `POST /api/bees/{id}/actions/wake`.

Every async write goes through `advance`: one transaction runs `GroveRepository.transitionIfState` (`UPDATE … WHERE id = ? AND state = ?`) and, only if it updated a row, saves the fruit rows and publishes `GroveStateChangedEvent`. Zero rows means the grove moved on, so the stop or start abandons without further provider calls or writes; a `clear` committed mid-stop or mid-start therefore wins.

Any async failure sets the grove `BLIGHTED` (through `advance`, expecting the state the step left) with the plot as last known. A failed stop or a failed ripen leaves the plot `ROOTED`, so the grove can be stopped again; a failed water leaves it `DORMANT`, so it can be started again.

---

## 4. Container Management (FruitGrower)

`nursery/src/main/java/dev/orchard/nursery/FruitGrower.java`

All container operations are executed remotely via SSH to the VM plot.

### Single Container Workflow
1. If seed has `image`: `docker pull {image}` then `docker run -d` with env vars, port forwards, workspace mount at `/workspace`
2. If seed has `dockerfilePath`: `docker build -t {name} -f {path} /workspace` then `docker run`
3. Extract port mappings via `docker port`
4. Execute `postCreateCommands` via `docker exec`

### Docker Compose Workflow
1. Discover services: `docker compose -f {path} config --services`
2. Create a `Fruit` record per service (primary service first)
3. Start all: `docker compose -f {path} up -d`
4. Match running containers to fruits by service name via `docker compose ps`
5. Execute `postCreateCommands` on the primary service container

### SSH Execution

`FruitGrower`'s container-management commands (this section) still go through `SshExecutor` directly, constructed inline per call — deliberately out of scope for the `Vine`/`GroveProvider` seam introduced in #86. Control-plane exec elsewhere (e.g. `GroveService`'s cloud-init polling, git clone, and devcontainer.json read/write in [§3.1](#31-end-to-end-sequence)) instead goes through `GroveProvider.vine(plot).commands()`, a `CommandRunner`, whose only implementation today (`SshVine`) is backed by `SshExecutor` for VM substrates.

`SshExecutor` itself:
- Invokes the local `ssh` binary via `ProcessBuilder`
- Options: `StrictHostKeyChecking=no`, `UserKnownHostsFile=/dev/null`, `ConnectTimeout=10`
- Key: `~/.ssh/orchard_ed25519`
- Connects as user `cultivator` on the VM plot's host and SSH port

---

## 5. Startup Reconciliation

`trellis/src/main/java/dev/orchard/trellis/GroveReconciler.java`

Runs on application startup via `ApplicationRunner`:

| Previous State | Action | New State |
|---------------|--------|-----------|
| `FLOURISHING` + `inspect` returns `ROOTED` | Leave as-is | `FLOURISHING` |
| `FLOURISHING` + `inspect` returns another state or throws | Mark failed | `BLIGHTED` |
| `PREPARING`, `PLANTING`, `GROWING` | Stuck from previous run | `BLIGHTED` |
| `CLEARING` | Interrupted teardown, not re-attempted | `ORPHANED` |

Liveness is `registry.require(plot.providerId()).inspect(plot)`, never a socket probe. Any exception (unregistered provider, failed `inspect`, unreadable or missing plot) counts as unreachable and is logged at ERROR.

---

## 6. Prebuild Architecture (Greenhouse)

The `greenhouse` module provides pre-built container images to reduce grove provisioning latency:

- **PrebuildService**: Orchestrates image building from devcontainer specs
- **ImageBuilder**: Builds Docker images and pushes to a registry
- **PrebuildScheduler**: Periodically refreshes pre-built images
- **GreenhouseAutoConfiguration**: Spring Boot auto-configuration for the module

When a grove is provisioned and a matching prebuild exists, the container image is pulled from the registry instead of being built from scratch on the VM.

---

## 7. Scalability and Performance Considerations

### Current Architecture Constraints

| Constraint | Detail |
|-----------|--------|
| **Single instance** | Trellis runs as a single Spring Boot process. No clustering. |
| **In-memory process tracking** | `QemuGroveProvider` tracks VM processes in a `ConcurrentHashMap<UUID, Process>`, lost on restart (reconciler compensates). |
| **In-memory message broker** | STOMP uses Spring's simple in-memory broker. SSE emitters are also in-memory. |
| **SSH-based execution** | All VM interaction forks a local `ssh` process per command. No persistent connections or agent. |
| **Virtual threads** | `FruitGrower` and `QemuGroveProvider` use `Executors.newVirtualThreadPerTaskExecutor()` for concurrent provisioning. |

### Horizontal Scaling Path

```mermaid
graph LR
    subgraph "Current (Single Node)"
        T1[Trellis] --> PG[(PostgreSQL)]
        T1 --> QEMU[QEMU VMs]
    end

    subgraph "Scaled (Multi-Node)"
        LB[Load Balancer] --> T2[Trellis N]
        LB --> T3[Trellis N+1]
        T2 --> PG2[(PostgreSQL)]
        T3 --> PG2
        T2 --> MQ[Redis / RabbitMQ]
        T3 --> MQ
        T2 --> Cloud[Cloud Provider APIs]
        T3 --> Cloud
    end
```

**Key changes needed for horizontal scaling**:

1. **External message broker**: Replace the in-memory STOMP broker with Redis or RabbitMQ so WebSocket messages are broadcast across all Trellis instances.

2. **Cloud providers over QEMU**: QEMU requires local process management. Cloud providers (EC2, GCE, Azure) use stateless API calls that work from any Trellis instance. State is already persisted in PostgreSQL.

3. **SSH connection management**: Consider SSH connection pooling or a persistent agent running on VMs to reduce overhead from forking `ssh` processes per command.

4. **Database**: PostgreSQL supports read replicas for scaling read traffic. No sharding needed at typical CDE scale (hundreds to low thousands of groves).

5. **Prebuild distribution**: Container registry becomes a shared resource. Greenhouse builds can be offloaded to a dedicated worker or CI pipeline.

### Performance Characteristics

| Phase | Typical Latency | Bottleneck |
|-------|----------------|------------|
| API response (plantGrove) | < 100ms | Synchronous — returns immediately |
| VM boot (QEMU) | 30-60s | QEMU process startup + SSH readiness |
| Cloud-init | 1-3 min | Package installation (docker, git) |
| Repository clone | 5-30s | Network speed + repo size |
| Container pull/build | 30s-2 min | Image size + registry speed |
| **Total provisioning** | **2-5 min** | Cloud-init dominates |

**Optimizations available**:
- Prebuilds (Greenhouse) eliminate container build time
- Custom VM images with docker/git pre-installed eliminate cloud-init wait
- Shallow clones (`--depth 1`) already used to minimize clone time
- Virtual threads prevent thread pool exhaustion under concurrent provisioning

### Reliability Mechanisms

| Mechanism | Purpose |
|-----------|---------|
| `TransactionSynchronization.afterCommit()` | Ensures DB row exists before async provisioning begins |
| `GroveReconciler` | Catches stuck/orphaned groves on startup |
| VM reachability check | TCP probe before attempting container cleanup during teardown |
| Fallback devcontainer | Default Ubuntu image + ports if no devcontainer.json found |
| `FAIL_ON_UNKNOWN_PROPERTIES=false` | Forward-compatible JSON parsing in CLI |
