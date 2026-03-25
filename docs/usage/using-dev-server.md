# Using the Dev Server

This guide covers running Orchard locally on your machine to provision development environments using the local QEMU-based VM provider.

For the themed naming glossary, see [README.md](../../README.md#themed-glossary).

## Prerequisites

| Requirement | Version | Purpose |
|-------------|---------|---------|
| Java | 21+ | Backend (Trellis) and CLI (Trowel) |
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

The default configuration points to `http://localhost:8080`, which matches the local Trellis server.

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
| VM stuck in GERMINATING | QEMU not installed or base image missing | Verify `qemu-system-x86_64` is on PATH and base image exists |
| Connection refused on :8080 | Trellis not running | Run `./gradlew :trellis:bootRun` |
| PostgreSQL connection error | Database not started | Run `docker compose up -d postgres` |
| KVM permission denied | User not in kvm group (Linux) | `sudo usermod -aG kvm $USER` or set `enable-kvm: false` |
