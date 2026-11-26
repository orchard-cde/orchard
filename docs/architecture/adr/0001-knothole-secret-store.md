# ADR 0001: Knothole, the Orchard secret store

**Status:** Accepted, implemented in #329 (2026-11-26). Transport hardening is a follow-up (#330).

## Context

Orchard had no secret store. `CredentialResolver` had only a no-op implementation, a devcontainer.json `secrets` block was parsed and then ignored (#268, the unfinished half of #104), and every design that needed a token worked around the gap: Bee API keys proposed their own encrypted table (#45), private clones had nowhere to keep a credential (#79), and the planned registry cache and SCM proxy each assumed anonymous upstreams. Any enterprise pilot ranks secrets a blocker.

The constraints that shaped the design:

- A value must never appear in a command line, a log line, an API response, a database row, or on a seedling's persistent disk.
- The database is not the long-term home of values. Single-host installs must work with nothing but the Trellis host; multi-instance installs (#275) will need an external backend.
- A grove's cultivator reaches the fruit through SSH and VS Code sessions that land on the VM, not inside the container, so a secret delivered only into the container is not enough on VM plots.
- Nothing in v1 should force a new process into Arboretum deployments or the dev-server.

## Decision

**Knothole is the one secret store for the Orchard ecosystem.** A value is stored once under a name by its owner; consumers resolve it by name or by reference; the value never leaves the control plane except into the place that uses it.

1. **Ownership.** Each service owns entry of its own credentials. Knothole stores the value; the owning service stores a reference. A token that serves two services is stored twice; Knothole does not model sharing.
2. **Scopes.** Two: `CULTIVATOR` (owned by one cultivator, matched to a grove's declared secrets by name) and `SERVICE` (owned by a named platform service, never delivered to a grove). v1 builds the cultivator scope end to end and reserves the service scope in the schema and model.
3. **Placement: a module inside Trellis.** A `knothole` Gradle module in the monorepo, metadata in `roots`, REST under `/api/secrets` in Trellis, values behind a `SecretVault` interface. The module depends on `core` only, so a REST front and its own process can be put on it later without rewriting it.
4. **The database holds metadata, not values.** The `secrets` table carries name, scope, owner, timestamps and an opaque vault key. The value lives in the vault backend. The reference a consumer holds is the secret's id; the vault key is internal and changes on every write, so no state ever straddles the database commit. v1's backend is a file vault on the Trellis host: one AES-256-GCM entry per vault key under a master key created on first use with owner-only permissions, the way Fence creates its signing key.
5. **Direction: a separate process over an external backend.** The end state is Knothole as its own service, authenticated with Fence client-credentials tokens like the gateway, reading values from AWS SSM Parameter Store or Secrets Manager, HashiCorp Vault, or the file vault for single-host installs. The file vault cannot serve more than one Trellis instance, so #275 forces this move when it comes.
6. **First consumer: devcontainer `secrets`.** A grove whose seed declares `secrets` receives the cultivator's stored values as environment variables. Delivery happens after `devcontainer up` and again on every `start`, over stdin into a memory-backed file: `/dev/shm/orchard/secrets.env` inside the fruit and, on VM plots, `/run/orchard/secrets/secrets.env` on a read-only tmpfs on the VM, with an `/etc/profile.d` hook so login shells and bees see them. A declared secret with no stored value is a warning on the grove (`missingSecrets`); a stored value that cannot be delivered blights the grove rather than degrading silently. Container plots launched with `--ipc=host` are refused for groves that declare secrets, because `/dev/shm` would be the host's.
7. **Rotation signal.** Overwriting a secret keeps its id, replaces its vault entry under a new key, and bumps `rotatedAt` in the same commit. Consumers that cache a value re-authenticate on that signal. Rotation of the master key itself is out of scope for v1.
8. **Entry points.** The REST API (`GET /api/secrets`, `PUT /api/secrets/{name}`, `DELETE /api/secrets/{name}`), `trowel secret set|list|rm`, a Canopy settings page (orchard-ui#165) and VS Code extension commands (orchard-vscode-extension#113). Values are written through these only; the API never returns one.
9. **Supersedes #45's own table.** The `bee_credentials` table with its own AES-GCM is dropped. #45 stays open as the Bee-key consumer, implemented as a `CredentialResolver` over Knothole with a conventional secret name per bee type.

**Name.** A knothole is the hollow in a trunk where small things are tucked away, out of sight.

## Alternatives considered

- **A table per consumer** (#45's original shape). Every consumer re-implements encryption, rotation and audit; values end up in the database. Rejected.
- **An external secret manager as the only backend.** Correct end state, but it makes the single-host and dev-server installs depend on a service they do not have. Deferred to decision 5.
- **A separate Knothole process from day one.** Right boundary, wrong time: it adds a service to Arboretum and the dev-server before any consumer outside Trellis exists. The module boundary gives the same seam for free until #275 or an external consumer forces the split.
- **Delivery through `devcontainer up --remote-env` or `containerEnv`.** Puts the value on a command line and in the container's config, both of which are visible to anyone who can inspect the engine. Rejected in favor of stdin into tmpfs.

## Consequences

- Transport: in #329 values travel as plaintext JSON inside TLS. Client-side sealing to a Trellis public key (HPKE) is tracked in #330, with the client halves in orchard-ui#173 and orchard-vscode-extension#114.
- Every future credential-holding feature (Bee keys, private clone, registry and SCM upstreams, MCP server tokens) resolves through Knothole rather than carrying its own storage.
- Delivery to the grove is lifetime-scoped: a bee inside the fruit sees the same secrets the cultivator does. Task-scoped or shorter-lived grants to an agent are a later design, not something v1 forecloses.
- Secrets are per cultivator; a team-shared secret is stored by each cultivator who needs it until a team scope exists.

## Open questions

1. **When the process split happens.** Triggers: a second Trellis instance (#275), or a consumer outside Trellis that should not reach into Trellis's database.
2. **First external backend.** SSM Parameter Store is the obvious fit for the AWS deployment template, which already keeps the seedling SSH key there; Secrets Manager costs more and adds rotation hooks; Vault suits self-hosted estates. Decide with the first AWS pilot.
3. **Narrower grants to agents.** Whether a bee session should receive a subset of the cultivator's secrets, or shorter-lived ones, once an agent session is a distinct principal.

## Related

- #328 (tracking issue), #329 (implementation), #330 (transport sealing), #268, #45, #79, #275.
- [Platform Architecture](../platform-architecture.md) for the module graph `knothole` now sits in.
