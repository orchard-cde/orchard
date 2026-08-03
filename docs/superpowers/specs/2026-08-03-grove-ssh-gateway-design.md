# Grove SSH Gateway, Owner-Token Fallback & Trowel SSH Key CLI

## Summary

Close out orchard-cde/orchard#200 scopes 3-5: introduce a dedicated SSH gateway process so **no seedling ever exposes sshd directly**; add an ephemeral owner-token fallback for authenticated SSH access; and give the Trowel CLI an `ssh-key` subcommand to generate, register, list, and remove public keys. Scopes 1-2 (registration API + baking registered keys into seedlings) are already complete and green on `feat/grove-ssh-gateway`.

## Motivation

Today `trowel grove connect` hands the user an `ssh` command that targets the seedling IP/port directly with the shared trellis key baked in:

```
ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i <shared-key> -p <port> cultivator@<seedling-ip>
```

That means (a) seedlings must expose sshd on a routable address, (b) every client uses the same long-lived shared credential, and (c) there is no per-grove authorization at the network boundary. The SSH gateway fixes all three: clients connect to one gateway, authenticate with their **own** registered key (or a short-lived owner token), and the gateway reaches each seedling over the loopback using the internal key. Registered-key auth was already delivered (scopes 1-2); this spec adds the gateway, the token fallback, and the CLI.

## Architecture

```
                        public SSH                       internal SSH
  cultivator ── ssh <grove-id>@gateway ──► gateway ──────────────────► seedling
                      (port 2222)             │  (port 22, loopback only,
                                             │   internal key, colocated)
        ┌────────────────────────────────────┴──────────────┐
        │ gateway  (new module: gateway/)                    │
        │  GroveRelayServer     MINA SSHD listener           │
        │  GroveResolver        username -> grove id         │
        │  KeyAuthenticator     publickey auth (registered)  │
        │  OwnerTokenAuth       password auth (gateway token)│
        │  SeedlingRelay        channel relay (jumphost)     │
        │  TrellisApiClient     HTTP -> trellis (service tok)│
        └───────────────┬────────────────────────────────────┘
                        │ HTTP /api/gateway/** (client_credentials)
                        ▼
                      trellis ──► fence (OAuth2 issuer)
```

- **No seedling exposes sshd directly.** The gateway is the only process that can reach seedling port 22, and only because it is colocated with trellis on the same host (QEMU hostforwarding binds seedlings to the `127.0.0.1` loopback, so this is also a hard deployment constraint). The internal trellis→seedling key never leaves that host.
- The gateway is a **new Spring Boot module `gateway/`** registered in `settings.gradle.kts`. It depends only on `core`; it does **not** depend on `roots`/JPA. It reaches trellis over HTTP with an OAuth2 `client_credentials` service token.
- SSH listener default port **2222**. Spring Boot's own `server.port` is internal/admin only.
- The gateway's host key (its own identity as an SSH server) is generated on first run and persisted (same pattern as fence's `SigningKeyConfig`).

## Components

### gateway (new module)

| Component | Responsibility |
|-----------|----------------|
| `GroveRelayServer` | Boots MINA SSHD `SshServer` on `orchard.gateway.ssh-port`; installs the publickey + password authenticators, the shell/exec factory, and the direct-tcpip handler. Persists its host key on first run. |
| `GroveResolver` | Parses the SSH username as the grove id (UUID); calls trellis to resolve the route. Rejects malformed ids and non-routable groves. |
| `KeyAuthenticator` | MINA `PublickeyAuthenticator`. Resolves the route, fetches the cultivator's registered key fingerprints from trellis, computes the offered key's fingerprint, and matches. |
| `OwnerTokenAuthenticator` | MINA `PasswordAuthenticator`. Validates the presented JWT (signature via fence JWKS, `aud=orchard-gateway`, `scope=gateway-ssh`, expiry), extracts `email`, and authorizes via trellis `authorize-owner`. |
| `SeedlingRelay` | Jumphost channel relay: for every client channel (shell/exec/sftp subsystem/direct-tcpip) opens an SSH **client** session to `seedlingIp:seedlingPort` with the internal key and pumps the matching channel bidirectionally. Preserves pty, scp/sftp, and port-forwarding (two-hop direct-tcpip). |
| `TrellisApiClient` | HTTP client to trellis `/api/gateway/**` using an OAuth2 `client_credentials` token from fence. |

### trellis (additions)

| Component | Responsibility |
|-----------|----------------|
| `GatewayGroveController` | `GET /api/gateway/groves/{id}` → `{cultivatorId, seedlingIp, seedlingPort, state}` (404 unknown; reject when the seedling is not running — no IP/port or seedling state != SAPLING); `GET /api/gateway/cultivators/{id}/keys` → registered `{fingerprint, publicKey}`; `POST /api/gateway/authorize-owner {groveId, email}` → route or 403. All under `/api/gateway/**`, protected by the existing `oauth2ResourceServer` chain (service token passes; dev chain passes). |
| `CultivatorAuthFilter` fix | In `doFilterInternal`, **skip cultivator resolution when the JWT has no `email` claim** (debug log + pass through). `client_credentials` tokens have sub = client id and no email; today the filter would fail or create a garbage cultivator. User tokens always carry email (openid scope). |
| `CultivatorService.findByEmail` | New `Optional<Cultivator> findByEmail(String email)` lookup for `authorize-owner`. |

### fence (additions)

| Component | Responsibility |
|-----------|----------------|
| `GatewayTokenController` + `GatewayTokenService` | `POST /gateway-token`, requiring a valid fence-issued bearer token. Mints a short-lived (5 min) JWT: `{sub: <upstream sub>, email, scope: gateway-ssh, aud: orchard-gateway, exp, iat, jti}`, signed with the existing `SigningKeyConfig` RSA key. |
| Gateway security chain | A `SecurityFilterChain` for `/gateway-token` that validates the presented bearer token locally against fence's own `JWKSet` (no JWKS fetch). Ordered ahead of the OAuth2 endpoints matcher. |
| `orchard-gateway` client | New confidential registered client (`client_credentials`), secret from `FENCE_GATEWAY_CLIENT_SECRET`. This is the credential the gateway uses to mint its own service token for trellis calls. |

`TokenClaimsConfig` is **not modified** (it only shapes `OidcUser` principals for the interactive flows).

## Auth Flows

### Registered-key auth (primary)

1. Client: `ssh <grove-id>@<gateway-host> -p <gateway-port> -i ~/.orchard/keys/<name>`.
2. `KeyAuthenticator` resolves the grove (`GET /api/gateway/groves/{id}`), fetches the cultivator's registered fingerprints (`GET /api/gateway/cultivators/{id}/keys`), formats the offered `PublicKey` to the ssh wire line, computes its fingerprint with the core algorithm, and matches.
3. Match → session context `{groveId, seedlingIp, seedlingPort}`; any subsequent channel is relayed.

Fingerprint matching reuses `SshPublicKey.fingerprint` (made public static). Registered fingerprints are already persisted (V8 migration).

### Owner-token auth (fallback, scope 4)

1. Client POSTs its OAuth2 access token to fence `POST /gateway-token` and receives the short-lived gateway JWT.
2. Client: `ssh <grove-id>@<gateway-host>` and enters the JWT as the SSH password.
3. `OwnerTokenAuthenticator` validates signature/`aud`/`scope`/`exp` via fence JWKS, extracts `email`.
4. `POST /api/gateway/authorize-owner {groveId, email}` → trellis finds the cultivator by email and verifies `grove.cultivatorId` matches → route or 403.

### Authorization summary

- Key auth proves ownership intrinsically: keys are stored per-cultivator and the grove lookup returns that cultivator.
- Token auth proves ownership via the email → cultivator → grove check in `authorize-owner`.
- The gateway never sees a client's private key material and never receives the internal trellis key from anywhere except the shared host filesystem.

## Relay Flow

After auth, `SeedlingRelay` implements the `ssh -J` model using MINA SSHD:

- A custom `ShellFactory`/`CommandFactory` returns a command that opens an SSH client channel to the seedling and pumps bytes/events both ways.
- The `sftp` subsystem is forwarded by relaying the subsystem channel.
- `direct-tcpip` requests are turned into two-hop forwardings (client → gateway → seedling → target), preserving local/remote port-forwarding semantics.

## Configuration

### gateway `application.yml`

| Property | Env | Default |
|----------|-----|---------|
| `orchard.gateway.ssh-port` | `GATEWAY_SSH_PORT` | `2222` |
| `orchard.gateway.host-key-path` | `GATEWAY_HOST_KEY_PATH` | `~/.orchard/gateway-host-key` |
| `orchard.gateway.internal-ssh-key-path` | `GATEWAY_INTERNAL_SSH_KEY_PATH` | `~/.ssh/orchard_ed25519` (same as trellis's `QemuConfigProperties.sshKeyPath` / `QemuPlatformDefaults.defaultSshKeyPath()`) |
| `orchard.gateway.fence.issuer-uri` | `GATEWAY_FENCE_ISSUER_URI` | `http://localhost:7779` |
| `orchard.gateway.oauth2.client-id` | `GATEWAY_OAUTH2_CLIENT_ID` | `orchard-gateway` |
| `orchard.gateway.oauth2.client-secret` | `GATEWAY_OAUTH2_CLIENT_SECRET` | — |
| `orchard.gateway.trellis.base-url` | `GATEWAY_TRELLIS_BASE_URL` | `http://localhost:8080` |

Bound via a `GatewayProperties` `@ConfigurationProperties` bean, mirroring `FenceProperties`.

### trowel config

New `OrchardConfig` keys: `gateway.host` (default `localhost`), `gateway.port` (default `2222`), `sshKeyDir` (default `~/.orchard/keys`). Exposed through `ConfigCommand`.

### dev-server

`DevServerCommand` gains an `orchard-gateway` binary alongside core/fence: start/stop/status, pid `~/.orchard/run/orchard-gateway.pid`, log `~/.orchard/logs/orchard-gateway.log`. It passes `--orchard.gateway.ssh-port`, `--orchard.gateway.internal-ssh-key-path` (the same path trellis uses), and `--orchard.gateway.fence.issuer-uri`.

## Trowel CLI (scope 5)

```
trowel ssh-key add [--name NAME] [--path PATH]
trowel ssh-key list
trowel ssh-key remove <id>
```

- `add` — default generates an ed25519 keypair under `~/.orchard/keys/<name>` (default name `default` or derived from `--cultivator`), prints the fingerprint, and registers the public half via `POST /api/ssh-keys`. `--path` registers an existing public key without generating.
- `list` — `GET /api/ssh-keys`, prints a name + fingerprint table.
- `remove <id>` — `DELETE /api/ssh-keys/{id}` with a confirmation prompt.
- `trowel grove connect` — builds `ssh <groveId>@<gateway.host> -p <gateway.port> -i <sshKeyDir>/<name>` instead of executing the old baked-in string. `Grove.getSshConnectionString()` remains a display helper but now points at the gateway and drops the shared-key flags. **All three touchpoints in `GroveCommand.java` must change, not just the exec:** (a) the connect subcommand's `sshConnectionString().split(" ")` → `ProcessBuilder` exec (~line 314), (b) the same connect flow's `Command:` echo (~line 310), and (c) the `printGrove` status view `Connect:` line (~lines 379-381). Missing (c) leaves the status view printing a stale/undialable command.
- New client-side `SshKeyResponse` record on `OrchardClient`.
- `SshKeyCommand` follows the `GroveCommand`/`BeeCommand`/`LoginCommand` picocli pattern (inner static subcommands, `@ParentCommand` chain, exit 0/1).

## Testing

| Module | Tests |
|--------|-------|
| gateway | `GroveResolverTest`, `KeyAuthenticatorTest` (registered match / unknown key / grove-not-found), `OwnerTokenAuthenticatorTest` (valid JWT via Nimbus test signer; expired / wrong aud / wrong scope / non-owner → reject), `TrellisApiClientTest` (stubbed HTTP). One integration test boots the MINA server on an ephemeral port with stub trellis and round-trips an exec connection; if flaky in CI it lives in `integration-tests/`. |
| fence | `GatewayTokenControllerTest` — valid bearer → 200 with `scope=gateway-ssh`/`aud`/5-min exp; missing/invalid bearer → 401. |
| trellis | `GatewayGroveControllerTest` (routing + reject non-ready), `CultivatorAuthFilterTest` case for a no-email-claim JWT passing through without cultivator resolution. |
| trowel | `SshKeyCommandTest` (generate/register/list/remove against stubbed client). |

`./gradlew build` stays green per step.

## Scope Mapping

- **Scope 3 (SSH gateway)** — gateway module, MINA SSHD, routing, jumphost relay, trellis gateway API, service-token auth, `CultivatorAuthFilter` email-claim fix.
- **Scope 4 (owner-token fallback)** — fence `POST /gateway-token` + gateway password auth via JWKS; `TokenClaimsConfig` untouched.
- **Scope 5 (trowel ssh-key)** — `ssh-key add/list/remove`, `connect` rewire, config keys, dev-server gateway management.

## Out of Scope

- The `sshConnectionString` format change (drops `-i` + retargets `cultivator@ip` → `groveId@gateway-host`) is a **breaking change to an externally-consumed API field**: `GroveResponse.sshConnectionString` is serialized from `Grove.getSshConnectionString()` (GroveResponse.java:84) and is consumed by `orchard-vscode-extension`'s `connectGrove.ts` (paired issue **orchard-vscode-extension#47**) and likely Canopy/orchard-ui. This spec does not modify that contract directly, but the format change must land in coordination with #47 (or the field kept stable/versioned) or the extension breaks silently the moment this merges.
- Retiring the shared trellis key as a client-facing credential (spec follow-up; the key remains the internal gateway→seedling credential here).
- Per-grove delegated credentials for the gateway (noted as a future option; same-host colocation keeps the single internal key safe).
- Cloud (AWS EC2) routing changes — the gateway works for any provider once seedling IP/port is routable from the gateway host.
- WebSocket/UI (Canopy) integration with the gateway.
