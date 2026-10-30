# Using the Dev Server

This guide covers running Orchard locally on your machine to provision development environments using the local QEMU-based VM provider or the local container provider.

For the themed naming glossary, see [README.md](../../README.md#themed-glossary).

## Quick Start (recommended)

The fastest way to run the full stack locally is the `trowel dev-server` command, which launches the core API and the Canopy UI together using an embedded H2 database (no PostgreSQL required):

```bash
trowel dev-server start        # core API on :17778, Canopy UI on :17777
trowel dev-server start --no-ui  # core API only on :17778
trowel dev-server start --provider container  # groves on the local docker/podman engine (default: qemu)
```

Open <http://localhost:17777> for the UI. The CLI's default `server` (written by `trowel config init`) is `http://localhost:17778`, so it talks to this core automatically. See the [README](../../README.md#start-the-platform) for details.

The rest of this guide covers the **manual contributor workflow** — running Trellis from source — which is useful when developing the server itself.

## Prerequisites

| Requirement | Version | Purpose |
|-------------|---------|---------|
| Java | 25+ | Backend (Trellis) and CLI (Trowel) |
| Docker & Docker Compose | Latest | PostgreSQL and devcontainer runtime |
| QEMU | Latest | Local VM provisioning (`--provider qemu`) |
| docker or podman | Latest | Container provider (`--provider container`): the engine must be reachable (`docker info` or `podman info` succeeds) |
| devcontainer CLI | 0.87.0 | Container provider: `npm install -g @devcontainers/cli@0.87.0` |
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

## Container Provider

`--provider container` (`orchard.nursery.provider: container`) plants groves on the local machine instead of in a VM. Each plot is a bind-mounted workspace directory under `orchard.nursery.container.workspace-root` (default `${user.home}/.orchard/plots`) and the fruit is a devcontainer started by the host's `devcontainer` CLI on the docker or podman engine. Git and the container CLIs run on the host; commands into the grove run through `<runtime> exec`.

The container provider runs repository-controlled commands and mounts with the trellis user's rights on the host: a repo's `initializeCommand` or devfile `preStart` runs in the host shell, and `devcontainer up` honours its `runArgs` and `mounts`. Use it single-tenant, with trusted repositories only.

With the fence enabled, `dev-server start` records `fence_server = http://localhost:<fence-port>` on the active target when that target points at the dev-server's own core URL, so `trowel login` needs no flags; if that changes the target's fence server it clears the stored tokens and asks you to run `trowel login` again. `trowel login` resolves the fence server from `--fence-server`, then `FENCE_SERVER_URL`, then the active target's `fence_server`, and fails otherwise.

`trowel grove connect <id>` goes through the gateway for every grove and authenticates with the target's provisioned key only (`IdentitiesOnly=yes`). `trowel ssh-key add` records that key. With the fence enabled, `dev-server start` (background or `-f`) registers a `dev-server` key once core and the gateway are healthy, when you have logged in; otherwise it prints `connect needs a registered key: run trowel login, then trowel ssh-key add`. It does nothing when the active target points at another server. `--no-auth` and `--no-gateway` disable connect. The gateway address comes from `orchard.gateway.ssh-host` and `orchard.gateway.ssh-port`; on any non-local deployment set `orchard.gateway.ssh-host` to the gateway's public host.

Container groves currently have an interim shell: no sftp, no port forwarding and no window-resize, and on docker the shell has no TTY (no line editing), while podman gets one. A future Orchard relay agent removes these limits.

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

`trowel config init` defaults the server to `http://localhost:17778` (the `trowel dev-server` core port). When running Trellis manually via `bootRun` it listens on `8080`, so point the CLI at it explicitly:

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
| Container grove BLIGHTED at plant | No reachable engine or wrong devcontainer CLI version | Check `docker info`/`podman info` and `devcontainer --version` prints `0.87.0` |
| VM stuck in SPROUTING | QEMU not installed or base image missing | Verify `qemu-system-x86_64` is on PATH and base image exists |
| Connection refused on :8080 | Trellis not running | Run `./gradlew :trellis:bootRun` |
| PostgreSQL connection error | Database not started | Run `docker compose up -d postgres` |
| KVM permission denied | User not in kvm group (Linux) | `sudo usermod -aG kvm $USER` or set `enable-kvm: false` |
