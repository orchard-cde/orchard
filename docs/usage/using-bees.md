# Using Bees

A bee is an AI coding assistant installed into a grove. Bees run next to the code in the grove's workspace, and every `trowel bee` command acts on one grove.

## Supported Types

| Type argument | Bee type | Status |
|---------------|----------|--------|
| `opencode` | `OPENCODE` | Installable: `OpencodeBeeKeeper` is implemented |
| `claude-code`, `gemini`, `codex`, `kiro`, `custom` | `CLAUDE_CODE`, `GEMINI`, `CODEX`, `KIRO`, `CUSTOM` | Accepted by the CLI and the enum, but no installer exists yet. Tracked in [#41](https://github.com/orchard-cde/orchard/issues/41), [#42](https://github.com/orchard-cde/orchard/issues/42) and [#43](https://github.com/orchard-cde/orchard/issues/43) |

The type argument is case-insensitive and accepts hyphens (`claude-code` maps to `CLAUDE_CODE`).

## Choosing a Grove

Every bee subcommand needs a grove ID. Pass it with `-g`/`--grove` or set `ORCHARD_GROVE_ID`. The option belongs to `trowel bee` itself, so it goes **before** the subcommand:

```bash
trowel bee -g <grove-id> list          # correct
trowel bee list -g <grove-id>          # rejected: -g is not accepted after the subcommand

export ORCHARD_GROVE_ID=<grove-id>     # alternative: omit -g entirely
trowel bee list
```

## Commands

| Command | Alias | Purpose |
|---------|-------|---------|
| `trowel bee -g <grove> install <type> [-v <version>]` | `add` | Install a bee into the grove. `-v` sets a version string such as `latest` or `1.2.3` |
| `trowel bee -g <grove> list` | `ls` | List the grove's bees with state and hatch time |
| `trowel bee -g <grove> show <bee-id>` | | Print one bee's ID, type, state, process ID and timestamps |
| `trowel bee -g <grove> wake <bee-id>` | | Wake a hibernating bee |
| `trowel bee -g <grove> smoke <bee-id>` | | Shut a bee down. Moves it to `SMOKED` |
| `trowel bee -g <grove> status` | | Swarm summary: total bees and a count per state |

Example:

```bash
trowel bee -g <grove-id> install opencode
trowel bee -g <grove-id> show <bee-id>
trowel bee -g <grove-id> list
```

`install` prints the new bee's details and the next command to run. Use `show` on that bee ID to follow it.

To see command help, use `trowel help bee`. `trowel bee --help` currently fails with `Unknown option: '--help'` because the `bee` command does not mix in the standard help options. That is a known defect and is not documented as expected behaviour.

## Lifecycle

| State | Meaning |
|-------|---------|
| `HATCHING` | Install in progress |
| `HIBERNATING` | Agent is installed but not running |
| `BUZZING` | Agent is running |
| `POLLINATING` | Agent is running and busy |
| `SMOKED` | Stopped, or failed (process crashed, unresponsive, or install failed) |

Transitions follow the domain rules in `BeeState.canTransitionTo`:

- `HATCHING` → `HIBERNATING`
- `HIBERNATING` → `BUZZING`
- `BUZZING` ⇄ `POLLINATING`, and either can drop to `HIBERNATING`
- Any state → `SMOKED`; `SMOKED` is terminal, so a smoked bee cannot be woken. Install a new bee instead.

`wake` corresponds to `HIBERNATING` → `BUZZING` and `smoke` to any state → `SMOKED`. For how the server drives these transitions, see [Bee Architecture](../architecture/bee-architecture.md).
