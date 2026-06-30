# Using the Dev Server

This guide covers running Orchard locally on your machine to provision development environments using the local QEMU-based VM provider.

For the themed naming glossary, see [README.md](../../README.md#themed-glossary).

## Quick Start (recommended)

The fastest way to run the full stack locally is the `trowel dev-server` command, which launches the core API and the Canopy UI together using an embedded H2 database (no PostgreSQL required):

```bash
trowel dev-server start        # core API on :7778, Canopy UI on :7777
trowel dev-server start --no-ui  # core API only on :7778
```

Open <http://localhost:7777> for the UI. The CLI's default `server` (written by `trowel config init`) is `http://localhost:7778`, so it talks to this core automatically. See the [README](../../README.md#start-the-platform) for details.

The rest of this guide covers the **manual contributor workflow** — running Trellis from source — which is useful when developing the server itself.

## Prerequisites

| Requirement | Version | Purpose |
|-------------|---------|---------|
| Java | 25+ | Backend (Trellis) and CLI (Trowel) |
| Docker & Docker Compose | Latest | PostgreSQL and devcontainer runtime |
| QEMU | Latest | Local VM provisioning |
| Node.js | 18+ | Web UI (Canopy) — optional |

## Starting the Backend

### 1. Start PostgreSQL

```bash
docker compose up -d postgres
```

This starts PostgreSQL on `localhost:5432` with database `orchard`, user `orchard`, password `orchard`.

### 2. Build the Project

```bash
./gradlew build -x test
```

### 3. Run Trellis (API Server)

```bash
./gradlew :trellis:bootRun
```

Trellis starts on **port 8080**. It runs Flyway migrations on startup to create the database schema.

### Configuration

Trellis configuration is in `trellis/src/main/resources/application.yml`:

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

Key settings:
- `orchard.qemu.base-image-path` — Path to the base QCOW2 VM image
- `orchard.qemu.vm-storage-path` — Directory for VM disk images
- `orchard.qemu.enable-kvm` — Enable KVM hardware acceleration (Linux only)

## Starting the Web UI (Optional)

The Canopy web UI is in a separate repository: [orchard-cde/orchard-ui](https://github.com/orchard-cde/orchard-ui). See that repository's README for setup instructions. By default, Canopy connects to Trellis at `http://localhost:8080`.

## Using the CLI

### Build the CLI

```bash
./gradlew :trowel:fatJar
```

### Configure for Local Server

```bash
java -jar trowel/build/libs/trowel-0.1.0-SNAPSHOT-all.jar config init
```

`trowel config init` defaults the server to `http://localhost:7778` (the `trowel dev-server` core port). When running Trellis manually via `bootRun` it listens on `8080`, so point the CLI at it explicitly:

```bash
java -jar trowel/build/libs/trowel-0.1.0-SNAPSHOT-all.jar config set --server http://localhost:8080
```

### Verify Connectivity

```bash
java -jar trowel/build/libs/trowel-0.1.0-SNAPSHOT-all.jar status
```

### Plant a Grove

```bash
java -jar trowel/build/libs/trowel-0.1.0-SNAPSHOT-all.jar grove plant https://github.com/user/repo
```

This provisions a QEMU VM locally, clones the repository, and starts the devcontainer. See [Using Orchard](using-orchard.md) for the full CLI reference.

## SSH Key Setup

Orchard uses an ED25519 SSH key pair for VM access. If you don't already have one:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/orchard_ed25519 -N ""
```

The key path can also be set via the `ORCHARD_SSH_PUBLIC_KEY` environment variable.

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| VM stuck in SPROUTING | QEMU not installed or base image missing | Verify `qemu-system-x86_64` is on PATH and base image exists |
| Connection refused on :8080 | Trellis not running | Run `./gradlew :trellis:bootRun` |
| PostgreSQL connection error | Database not started | Run `docker compose up -d postgres` |
| KVM permission denied | User not in kvm group (Linux) | `sudo usermod -aG kvm $USER` or set `enable-kvm: false` |
