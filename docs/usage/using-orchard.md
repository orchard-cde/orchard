# Using Orchard

This guide covers how to use Orchard against a deployed instance — planting groves, managing workspaces, and connecting to your development environments.

For the themed naming glossary, see [README.md](../../README.md#themed-glossary).

## Prerequisites

- The Orchard CLI (Trowel) — see [Building the CLI](#building-the-cli)
- The server URL for your Orchard deployment
- Your cultivator (user) ID

## Building the CLI

Clone the repository and build the CLI fat JAR:

```bash
git clone https://github.com/orchard-cde/orchard.git
cd orchard
./gradlew :trowel:fatJar
```

The JAR is at `trowel/build/libs/trowel-0.1.0-SNAPSHOT-all.jar`. For convenience, alias it:

```bash
alias trowel='java -jar /path/to/trowel-0.1.0-SNAPSHOT-all.jar'
```

## Configuration

Initialize your CLI configuration:

```bash
trowel config init
```

This creates `~/.orchard/config.toml` with a single target. Set your server URL and cultivator ID on the active target:

```toml
active = "local"

[targets.local]
server = "https://your-orchard-instance.example.com"
cultivator = "<your-uuid>"
```

The TOML format supports multiple named targets (e.g. `local`, `production`); switch between them with `trowel config target set <name>` or per-command with `--target`. A legacy `~/.orchard/config.properties` file is migrated automatically on first `trowel config init`.

Configuration can also be set via:
- CLI flags: `--server`, `--cultivator`, `--target`
- Environment variables: `ORCHARD_SERVER_URL`, `ORCHARD_CULTIVATOR_ID`, `ORCHARD_TARGET`

## Checking Server Status

Verify connectivity to your Orchard instance:

```bash
trowel status
```

## Planting a Grove

A grove is a workspace — a VM with a devcontainer provisioned from a Git repository.

```bash
trowel grove plant https://github.com/user/repo
```

Provisioning is asynchronous. The command returns immediately with a grove ID. The grove transitions through states: PREPARING → PLANTING → GROWING → FLOURISHING.

## Listing Groves

```bash
trowel grove list
```

## Viewing Grove Details

```bash
trowel grove show <grove-id>
```

This shows the grove's state, seedling (VM) info, fruit (container) status, and SSH connection details.

## Connecting to a Grove

Once a grove reaches FLOURISHING, connect via SSH:

```bash
trowel grove connect <grove-id>
```

You can also connect directly with VS Code Remote SSH using the SSH config from `trowel grove show`.

## Clearing a Grove

When you're done with a workspace:

```bash
trowel grove clear <grove-id>
```

This stops containers, destroys the VM, and transitions the grove to CLEARED.

## Web UI (Canopy)

Orchard also provides a web interface called Canopy for managing groves from your browser. Canopy is a separate application — see the [orchard-ui repository](https://github.com/orchard-cde/orchard-ui) for setup and usage.
