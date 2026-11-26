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

This shows the grove's state, plot info, fruit (container) status, and SSH connection details. A VM plot shows its host and SSH port; a container plot shows its runtime and has no SSH details, so `ssh-config` returns 409 for it.

## Secrets

A devcontainer declares the secrets it needs by name; the values live in Orchard's secret store (Knothole), encrypted at rest and scoped to your cultivator, never in the repository:

```json
"secrets": {
  "NPM_TOKEN": { "description": "Read token for the private registry", "documentationUrl": "https://example.com/tokens" }
}
```

```bash
trowel secret set NPM_TOKEN --value-file ~/npm-token   # or pipe it on stdin, or type it at the prompt
trowel secret list                                     # names and timestamps, never values
trowel secret rm -f NPM_TOKEN
```

Names match `[A-Za-z_][A-Za-z0-9_]{0,254}`; `PATH`, `IFS`, `ENV`, `BASH_ENV`, `LD_PRELOAD`, `LD_LIBRARY_PATH`, `HOME`, and `SHELL` are reserved. A value is never accepted as a command-line argument. `--value-file` stores the file's bytes verbatim, trailing newline included; stdin drops one trailing newline (`\n` or `\r\n`).

Each Trellis database needs its own vault directory (`orchard.knothole.vault-path`); the dev-server keeps its own under `~/.orchard/data/knothole`, beside its H2 database. The store's limits are properties with defaults: `orchard.knothole.max-value-size` (`64KB`), `stale-temp-age` (`60s`, after which an abandoned temp file in the vault is purged), `inspect-timeout` (`30s`, inspecting a container before delivery), and `delivery-timeout` (`60s`, each command that writes a secrets file or hook into a grove).

After the fruit is grown and after every start, Orchard writes each declared secret you have stored into the fruit at `/dev/shm/orchard/secrets.env` (memory-backed, owned by root, readable by the remote user's group) and, on a VM plot, onto the VM at `/run/orchard/secrets/secrets.env` (a tmpfs remounted read-only, readable by `cultivator`). `/etc/profile.d/orchard-secrets.sh`, also sourced from `/etc/bash.bashrc`, exports them into login and interactive shells in both places: `trowel grove connect`, SSH sessions, and bees. The hook relies on `/etc/profile` sourcing `/etc/profile.d/*.sh` (Debian, Ubuntu, Alpine, and Fedora images do) and, for zsh, on a guarded line Orchard appends to `/etc/zsh/zprofile` or `/etc/zprofile`. Values never reach the seedling's disk, the container's image layer, a command line, a log line, or the database.

A set, rotation, or removal reaches a running grove on its next start (`trowel grove stop`, then `trowel grove start`); a new grove gets the values current when it is planted. A declared secret you have not stored does not fail the grove: it flourishes without it, and `trowel grove show` lists it under "Missing secrets" with its description and documentation URL.

Limitations:

- Lifecycle commands (`postCreateCommand`, `postStartCommand`, and the rest) run inside `devcontainer up`, before delivery, and do not see secrets. Neither does a non-login, non-interactive shell such as `sh -c`; use `sh -lc`.
- A grove blights instead of flourishing without a stored secret it cannot deliver safely: a container plot whose fruit runs with `--ipc=host` (its `/dev/shm` is the host's), and the legacy provisioning path (`orchard.nursery.use-devcontainer-cli=false`), which cannot resolve the remote user.

## Connecting to a Grove

Once a grove reaches FLOURISHING, connect via SSH:

```bash
trowel grove connect <grove-id>
```

You can also connect directly with VS Code Remote SSH using the SSH config from `trowel grove show`.

`IdentitiesOnly=yes` still offers identities configured in `~/.ssh/config` (such as a `Host *` `IdentityFile`) alongside the target's key.

## Clearing a Grove

When you're done with a workspace:

```bash
trowel grove clear <grove-id>
```

This stops containers, destroys the VM, and transitions the grove to CLEARED.

## Web UI (Canopy)

Orchard also provides a web interface called Canopy for managing groves from your browser. Canopy is a separate application — see the [orchard-ui repository](https://github.com/orchard-cde/orchard-ui) for setup and usage.
