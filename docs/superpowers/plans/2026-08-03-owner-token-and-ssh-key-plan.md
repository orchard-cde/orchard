# Grove SSH Gateway — Owner-Token Auth & Trowel `ssh-key` CLI Implementation Plan (Phase 4 of 4)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the last two gaps in the grove SSH story. (1) **Gateway owner-token password auth** (scope-4): a user runs `ssh <grove-id>@<gateway-host>` and pastes a short-lived fence-issued JWT as the SSH password; the gateway validates the token against fence's JWKS, proves ownership via trellis, and relays exactly as publickey auth already does. (2) **Trowel `ssh-key` CLI** (scope-5): `trowel ssh-key add|list|remove` managing a local ed25519 keypair under `~/.orchard/keys` and registering its public half with trellis, so an end user actually has a path to register the keys the gateway authenticates.

**Architecture:** Phase 4 of 4 (see `docs/superpowers/specs/2026-08-03-grove-ssh-gateway-design.md` §Owner-Token Flow, lines 85–95). Gateway side reuses Phase 3's `GroveRelayServer` / `TrellisApiClient` / `KeyAuthenticator.ROUTE_KEY` and adds a MINA `PasswordAuthenticator` that decodes the pasted JWT with a `NimbusJwtDecoder` built from fence's JWKS (`{issuerUri}/oauth2/jwks` — the endpoint fence test-confirms, and trellis reaches the same issuer via `issuer-uri`), checks `aud=orchard-gateway` + `scope=gateway-ssh`, then calls trellis `POST /api/gateway/authorize-owner` and stashes the returned `GatewayRoute` on the `ServerSession` — identical downstream handling to `KeyAuthenticator`. CLI side is a new picocli subcommand group following the `GroveCommand`/`BeeCommand`/`LoginCommand`/`ConfigCommand` pattern, talking to the Phase 1 `POST/GET/DELETE /api/ssh-keys` endpoints via a `tools.jackson` `OrchardClient`.

**Tech Stack:** Java 25, Spring Boot 4.1, `spring-security-oauth2-jose` (JWT decode only — **no** servlet security), Apache MINA SSHD 2.19.0, Nimbus JOSE JWT, Picocli 4.7.7, Jackson (`tools.jackson`) TOML/JSON, JUnit 5, AssertJ, Mockito.

---

## Design decisions (resolving spec ambiguities for the executor)

1. **Use `spring-security-oauth2-jose` only, NOT `spring-boot-starter-oauth2-resource-server`.** The gateway never protects an HTTP resource — it decodes a JWT from a raw string password. Depending on `org.springframework.security:spring-security-oauth2-jose` (version from the Spring Boot BOM) brings `NimbusJwtDecoder` / `JwtValidators` / `Jwt` / `JwtException` without pulling `spring-security-web`, so the admin/actuator port keeps Phase 3's exact behavior and **no `SecurityFilterChain` is needed** (spring-security-web is what triggers Spring Boot's default "lock everything down" filter chain). Trellis uses the full resource-server starter because it actually guards `/api/**`; the gateway does not.
2. **JWKS URI is derived, not configured.** The spec table (lines 113–125) has no jwks key, and scope-4's guess of `/.well-known/jwks.json` does not match fence: Spring Authorization Server exposes `GET {issuer}/oauth2/jwks` (fence `AuthorizationServerConfigTest` confirms it) and fence permits `/.well-known/**`. Derive `{orchard.gateway.fence.issuer-uri}/oauth2/jwks` from the Phase 3 `GatewayProperties.Fence.issuerUri` (default `http://localhost:7779`) — the same issuer trellis already trusts.
3. **Decoder beans are constructed eagerly but fetch JWKS lazily.** `NimbusJwtDecoder.withJwkSetUri(...)` builds a `RemoteJWKSet` that does not hit the network until the first `decode()`. So the `JwtDecoder` bean and the `@SpringBootTest` contexts (e.g. `GroveRelayServerTest`) never touch fence at startup. Unit tests inject a `NimbusJwtDecoder.withPublicKey(...)` built from a locally generated RSA key instead — no HTTP involved.
4. **`OwnerTokenAuthenticator` takes `JwtDecoder` directly in its constructor** (the bean from `OwnerTokenAuthConfig`). No `ObjectProvider`, no lazy indirection — the bean is cheap to build, and the authenticator is trivially unit-testable with a public-key decoder.
5. **`GatewayRoute` (Phase 3) is reused as the owner-auth result.** Trellis's `GatewayGroveResponse` already uses identical component names (`seedlingIp`, `seedlingPort`, `state`), so `TrellisApiClient.authorizeOwner` deserializes with the same `objectMapper.readValue(...)` pattern as `resolveGrove` — no new gateway DTO.
6. **The token is the raw SSH password.** MINA offers password auth to clients whenever a `PasswordAuthenticator` is set; OpenSSH sends the pasted string verbatim via SSH_MSG_USERAUTH_REQUEST (RFC 4252 arbitrary bytes). JWTs are single-line, so no client-side changes are needed.
7. **Local keys live in `~/.orchard/keys/`, written in OpenSSH PEM.** `~/.orchard` is the established CLI home (same as `ConfigLoader.configDir()` / `DevServerCommand.orchardHome()`). The private key is written with `OpenSSHKeyPairResourceWriter` (the exact API the gateway module uses) so it is interoperable with plain `ssh -i`, and the public line is rendered with `PublicKeyEntry.toString(...)` (the same renderer the gateway's key-matching uses). Key generation is JCA `KeyPairGenerator.getInstance("Ed25519")` — JDK 15+, no extra dependency.
8. **Trowel gains `org.apache.sshd:sshd-core:2.19.0`** (same version as the gateway) for the key writer/parser. The repo already standardizes on this library in Phase 3; it is the only new trowel dependency.
9. **`grove connect` / `Grove.getSshConnectionString()` are NOT rewired** to the gateway or to `~/.orchard/keys` — that is an explicit Out-of-Scope constraint from the Phase 3 plan and the spec. This phase only gives the CLI the commands to *register* keys; the "connect through the gateway with the registered key" story already works via Phase 3 (`ssh -i ~/.orchard/keys/default <grove-uuid>@<gateway>`).
10. **No config-model changes.** `ssh-key` stores keys under a fixed `~/.orchard/keys` path and registers against the active target server (same `Trowel.getServerUrl()` / `getAuthProvider()` resolution every other command uses). The `OrchardConfig`/`Target` records and `ConfigLoader` TOML schema are untouched.
11. **Fence's `POST /gateway-token` endpoint is Phase 2** (already planned). Phase 4 does not touch fence, `TokenClaimsConfig`, or `SigningKeyConfig`. A CLI command to *fetch* an owner token is **out of scope** (not in scope-4/5); the user pastes the token from any client.

## Dependencies this phase assumes are already merged

- **Phase 1 (trellis):** `POST /api/ssh-keys` (201, `CultivatorAuthFilter` `cultivatorId` attribute), `GET /api/ssh-keys`, `DELETE /api/ssh-keys/{id}`; `CreateSshPublicKeyRequest(name, publicKey)`, `SshPublicKeyResponse(id, name, publicKey, fingerprint, createdAt)`; `GET/POST /api/gateway/authorize-owner` returning `GatewayGroveResponse(groveId, cultivatorId, seedlingIp, seedlingPort, state)` with 200 / 403 (not owner) / 404 (unknown or not routable) / 400 (blank email); **public static `SshPublicKey.fingerprint(String)`** in `core`.
- **Phase 2 (fence):** `orchard-gateway` confidential client + `POST /gateway-token` minting JWTs signed with `SigningKeyConfig`'s RSA key, claims `sub`/`email`, `aud=orchard-gateway`, `scope=gateway-ssh`, short TTL.
- **Phase 3 (gateway):** `gateway/` module with `GatewayProperties` (incl. `getFence().getIssuerUri()` default `http://localhost:7779`), `GatewayRoute`, `TrellisApiClient` (with `resolveGrove`), `KeyAuthenticator` (incl. `public static final AttributeKey<GatewayRoute> ROUTE_KEY`), `GroveRelayServer` (constructor `(GatewayProperties, HostKeyProvider, KeyAuthenticator, SeedlingRelay)`, installs `setPublickeyAuthenticator`), and `sshd-core:2.19.0` on the gateway classpath.

---

## File structure

```
gateway/
  build.gradle.kts                                  # + spring-security-oauth2-jose
  src/main/java/dev/orchard/gateway/
    config/OwnerTokenAuthConfig.java                # NEW: JwtDecoder bean from fence JWKS
    auth/OwnerTokenAuthenticator.java               # NEW: MINA PasswordAuthenticator
    api/TrellisApiClient.java                       # EDIT: + authorizeOwner(groveId, email)
    server/GroveRelayServer.java                    # EDIT: ctor + setPasswordAuthenticator
  src/test/java/dev/orchard/gateway/
    config/OwnerTokenAuthConfigTest.java            # NEW
    auth/OwnerTokenAuthenticatorTest.java           # NEW
    api/TrellisApiClientTest.java                   # EDIT: + authorizeOwner cases
trowel/
  build.gradle.kts                                  # + org.apache.sshd:sshd-core:2.19.0
  src/main/java/dev/orchard/trowel/
    Trowel.java                                     # EDIT: register SshKeyCommand
    ssh/SshKeyPaths.java                            # NEW
    ssh/SshKeyStore.java                            # NEW
    client/OrchardClient.java                       # EDIT: + ssh-key methods/records
    command/SshKeyCommand.java                      # NEW: add/list/remove
  src/test/java/dev/orchard/trowel/
    ssh/SshKeyStoreTest.java                        # NEW
    client/OrchardClientTest.java                   # EDIT: + ssh-key cases
    command/SshKeyCommandTest.java                  # NEW
```

---

### Task 1: Gateway owner-token password auth

**Files:**
- Modify: `gateway/build.gradle.kts`
- Create: `gateway/src/main/java/dev/orchard/gateway/config/OwnerTokenAuthConfig.java`
- Create: `gateway/src/main/java/dev/orchard/gateway/auth/OwnerTokenAuthenticator.java`
- Modify: `gateway/src/main/java/dev/orchard/gateway/api/TrellisApiClient.java`
- Modify: `gateway/src/main/java/dev/orchard/gateway/server/GroveRelayServer.java`
- Create: `gateway/src/test/java/dev/orchard/gateway/config/OwnerTokenAuthConfigTest.java`
- Create: `gateway/src/test/java/dev/orchard/gateway/auth/OwnerTokenAuthenticatorTest.java`
- Modify: `gateway/src/test/java/dev/orchard/gateway/api/TrellisApiClientTest.java`

- [ ] **Step 1: Add the dependency and write the failing tests**

In `gateway/build.gradle.kts`, add to `dependencies` (version managed by the Spring Boot BOM):

```kotlin
    implementation("org.springframework.security:spring-security-oauth2-jose")
```

Create `gateway/src/test/java/dev/orchard/gateway/auth/OwnerTokenAuthenticatorTest.java` — builds a real `NimbusJwtDecoder` from a locally generated RSA key and mints signed JWTs with Nimbus (the same `com.nimbusds` library that backs `spring-security-oauth2-jose`):

```java
package dev.orchard.gateway.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.orchard.gateway.api.GatewayRoute;
import dev.orchard.gateway.api.TrellisApiClient;
import org.apache.sshd.server.session.ServerSession;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OwnerTokenAuthenticatorTest {

    private static RSAKey rsaKey() throws Exception {
        return new RSAKeyGenerator(2048).generate();
    }

    private static JwtDecoder decoderFor(RSAKey key) throws Exception {
        return NimbusJwtDecoder.withPublicKey((RSAPublicKey) key.toRSAPublicKey()).build();
    }

    private static String sign(RSAKey key, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(key.toRSAPrivateKey()));
        return jwt.serialize();
    }

    private static JWTClaimsSet validClaims() {
        return new JWTClaimsSet.Builder()
                .subject("alice@example.com")
                .claim("email", "alice@example.com")
                .audience("orchard-gateway")
                .claim("scope", "gateway-ssh")
                .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                .build();
    }

    private static OwnerTokenAuthenticator authenticator(RSAKey key, TrellisApiClient trellis) throws Exception {
        return new OwnerTokenAuthenticator(decoderFor(key), trellis);
    }

    @Test
    void authenticate_acceptsOwnerTokenAndStoresRoute() throws Exception {
        RSAKey key = rsaKey();
        String token = sign(key, validClaims());
        TrellisApiClient trellis = mock(TrellisApiClient.class);
        UUID groveId = UUID.randomUUID();
        GatewayRoute route = new GatewayRoute(groveId, UUID.randomUUID(), "127.0.0.1", 22, "FLOURISHING");
        when(trellis.authorizeOwner(groveId, "alice@example.com")).thenReturn(Optional.of(route));
        ServerSession session = mock(ServerSession.class);

        assertThat(authenticator(key, trellis).authenticate(groveId.toString(), token, session)).isTrue();
        verify(session).setAttribute(eq(KeyAuthenticator.ROUTE_KEY), eq(route));
    }

    @Test
    void authenticate_rejectsExpiredToken() throws Exception {
        RSAKey key = rsaKey();
        String token = sign(key, new JWTClaimsSet.Builder()
                .subject("alice@example.com").claim("email", "alice@example.com")
                .audience("orchard-gateway").claim("scope", "gateway-ssh")
                .expirationTime(new Date(System.currentTimeMillis() - 60_000))
                .build());

        assertThat(authenticator(key, mock(TrellisApiClient.class))
                .authenticate(UUID.randomUUID().toString(), token, mock(ServerSession.class))).isFalse();
    }

    @Test
    void authenticate_rejectsTokenWithoutGatewayScope() throws Exception {
        RSAKey key = rsaKey();
        String token = sign(key, new JWTClaimsSet.Builder()
                .subject("alice@example.com").claim("email", "alice@example.com")
                .audience("orchard-gateway").claim("scope", "other")
                .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                .build());

        assertThat(authenticator(key, mock(TrellisApiClient.class))
                .authenticate(UUID.randomUUID().toString(), token, mock(ServerSession.class))).isFalse();
    }

    @Test
    void authenticate_rejectsTokenForWrongAudience() throws Exception {
        RSAKey key = rsaKey();
        String token = sign(key, new JWTClaimsSet.Builder()
                .subject("alice@example.com").claim("email", "alice@example.com")
                .audience("some-other-service").claim("scope", "gateway-ssh")
                .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                .build());

        assertThat(authenticator(key, mock(TrellisApiClient.class))
                .authenticate(UUID.randomUUID().toString(), token, mock(ServerSession.class))).isFalse();
    }

    @Test
    void authenticate_rejectsTokenSignedByAnotherKey() throws Exception {
        String token = sign(rsaKey(), validClaims());

        assertThat(authenticator(rsaKey(), mock(TrellisApiClient.class))
                .authenticate(UUID.randomUUID().toString(), token, mock(ServerSession.class))).isFalse();
    }

    @Test
    void authenticate_rejectsTokenWhenOwnerIsNotAuthorized() throws Exception {
        RSAKey key = rsaKey();
        String token = sign(key, validClaims());
        TrellisApiClient trellis = mock(TrellisApiClient.class);
        when(trellis.authorizeOwner(any(), eq("alice@example.com"))).thenReturn(Optional.empty());

        assertThat(authenticator(key, trellis)
                .authenticate(UUID.randomUUID().toString(), token, mock(ServerSession.class))).isFalse();
    }

    @Test
    void authenticate_rejectsMalformedUsernameAndBlankPassword() throws Exception {
        OwnerTokenAuthenticator authenticator = authenticator(rsaKey(), mock(TrellisApiClient.class));
        assertThat(authenticator.authenticate("not-a-uuid", "whatever", mock(ServerSession.class))).isFalse();
        assertThat(authenticator.authenticate(UUID.randomUUID().toString(), "", mock(ServerSession.class))).isFalse();
        assertThat(authenticator.authenticate(UUID.randomUUID().toString(), null, mock(ServerSession.class))).isFalse();
    }
}
```

Create `gateway/src/test/java/dev/orchard/gateway/config/OwnerTokenAuthConfigTest.java`:

```java
package dev.orchard.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;

class OwnerTokenAuthConfigTest {

    @Test
    void ownerTokenDecoder_buildsFromFenceIssuerUri() {
        GatewayProperties properties = new GatewayProperties();
        JwtDecoder decoder = new OwnerTokenAuthConfig().ownerTokenDecoder(properties);
        assertThat(decoder).isNotNull();
    }
}
```

Run: `./gradlew :gateway:test --tests "dev.orchard.gateway.auth.*" --tests "dev.orchard.gateway.config.OwnerTokenAuthConfigTest"`
Expected: FAIL — `cannot find symbol: class OwnerTokenAuthenticator` / `method ownerTokenDecoder` / `method authorizeOwner`.

- [ ] **Step 2: Create `OwnerTokenAuthConfig`**

Create `gateway/src/main/java/dev/orchard/gateway/config/OwnerTokenAuthConfig.java` — mirrors how trellis validates fence JWTs (fetching JWKS from the fence issuer), derived per decision 2:

```java
package dev.orchard.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Decodes owner tokens minted by fence's POST /gateway-token (Phase 2) that
 * the CLI pastes as an SSH password. JWKS is fetched from fence's issuer, the
 * same way trellis validates fence JWTs via issuer-uri. The RemoteJWKSet
 * resolves lazily, so building this bean never touches the network.
 */
@Configuration
public class OwnerTokenAuthConfig {

    @Bean
    public JwtDecoder ownerTokenDecoder(GatewayProperties properties) {
        String issuerUri = properties.getFence().getIssuerUri();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(issuerUri + "/oauth2/jwks").build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuerUri));
        return decoder;
    }
}
```

- [ ] **Step 3: Add `TrellisApiClient.authorizeOwner`**

Modify `gateway/src/main/java/dev/orchard/gateway/api/TrellisApiClient.java`. Add imports `org.springframework.http.MediaType` and `java.util.Map`, then add (next to `resolveGrove`, same error-mapping idiom):

```java
    public Optional<GatewayRoute> authorizeOwner(UUID groveId, String email) {
        return restClient.post()
                .uri("/api/gateway/authorize-owner")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("groveId", groveId, "email", email))
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status == 200) {
                        return Optional.of(objectMapper.readValue(response.getBody(), GatewayRoute.class));
                    }
                    if (status == 400 || status == 403 || status == 404) {
                        return Optional.<GatewayRoute>empty();
                    }
                    throw new IllegalStateException("Unexpected status " + status + " from trellis");
                });
    }
```

- [ ] **Step 4: Create `OwnerTokenAuthenticator`**

Create `gateway/src/main/java/dev/orchard/gateway/auth/OwnerTokenAuthenticator.java`:

```java
package dev.orchard.gateway.auth;

import dev.orchard.gateway.api.GatewayRoute;
import dev.orchard.gateway.api.TrellisApiClient;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.session.ServerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static dev.orchard.gateway.auth.KeyAuthenticator.ROUTE_KEY;

/**
 * MINA PasswordAuthenticator for the owner-token flow: the user runs
 * {@code ssh <grove-id>@<gateway-host>} and pastes a short-lived fence JWT
 * (POST /gateway-token) as the password. The token must be signed by fence,
 * carry the gateway audience and scope, and the bearer must own the grove —
 * trellis authorize-owner enforces the last check. On success the resolved
 * route is stashed under the same {@code ROUTE_KEY} KeyAuthenticator uses.
 */
@Component
public class OwnerTokenAuthenticator implements PasswordAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(OwnerTokenAuthenticator.class);
    private static final String GATEWAY_AUDIENCE = "orchard-gateway";
    private static final String GATEWAY_SCOPE = "gateway-ssh";

    private final JwtDecoder jwtDecoder;
    private final TrellisApiClient trellisApiClient;

    public OwnerTokenAuthenticator(JwtDecoder jwtDecoder, TrellisApiClient trellisApiClient) {
        this.jwtDecoder = jwtDecoder;
        this.trellisApiClient = trellisApiClient;
    }

    @Override
    public boolean authenticate(String username, String password, ServerSession session) {
        UUID groveId;
        try {
            groveId = UUID.fromString(username);
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
        if (password == null || password.isBlank()) {
            return false;
        }

        final Jwt jwt;
        try {
            jwt = jwtDecoder.decode(password);
        } catch (JwtException e) {
            log.debug("Owner token rejected: {}", e.getMessage());
            return false;
        }

        List<String> audience = jwt.getAudience();
        if (audience == null || !audience.contains(GATEWAY_AUDIENCE)) {
            log.debug("Owner token rejected: wrong audience");
            return false;
        }
        List<String> scopes = jwt.getClaimAsStringList("scope");
        if (scopes == null || !scopes.contains(GATEWAY_SCOPE)) {
            log.debug("Owner token rejected: missing {} scope", GATEWAY_SCOPE);
            return false;
        }
        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            log.debug("Owner token rejected: no email claim");
            return false;
        }

        Optional<GatewayRoute> route = trellisApiClient.authorizeOwner(groveId, email);
        if (route.isEmpty()) {
            log.debug("Owner token rejected: {} is not an authorized owner of grove {}", email, groveId);
            return false;
        }
        session.setAttribute(ROUTE_KEY, route.get());
        return true;
    }
}
```

- [ ] **Step 5: Wire the authenticator into `GroveRelayServer`**

Modify `gateway/src/main/java/dev/orchard/gateway/server/GroveRelayServer.java` (Phase 3, Task 7). Add an import for `dev.orchard.gateway.auth.OwnerTokenAuthenticator`, add a field + constructor parameter, and install it in `start()` next to the publickey authenticator:

```java
    private final OwnerTokenAuthenticator ownerTokenAuthenticator;
```

```java
    public GroveRelayServer(GatewayProperties properties, HostKeyProvider hostKeyProvider,
                            KeyAuthenticator keyAuthenticator, OwnerTokenAuthenticator ownerTokenAuthenticator,
                            SeedlingRelay seedlingRelay) {
        this.properties = properties;
        this.hostKeyProvider = hostKeyProvider;
        this.keyAuthenticator = keyAuthenticator;
        this.ownerTokenAuthenticator = ownerTokenAuthenticator;
        this.seedlingRelay = seedlingRelay;
    }
```

In `start()`, directly after `server.setPublickeyAuthenticator(keyAuthenticator);`:

```java
        server.setPasswordAuthenticator(ownerTokenAuthenticator);
```

`GroveRelayServerTest` needs no change: it is `@SpringBootTest` + `@Autowired`, so Spring supplies the new bean, and the decoder bean is network-lazy (decision 3).

- [ ] **Step 6: Add `authorizeOwner` cases to `TrellisApiClientTest`**

Follow the existing `MockRestServiceServer` pattern in `gateway/src/test/java/dev/orchard/gateway/api/TrellisApiClientTest.java` (mirroring the `resolveGrove` tests):

```java
    @Test
    void authorizeOwner_returnsRouteForOwner() throws Exception {
        UUID groveId = UUID.randomUUID();
        GatewayRoute expected = new GatewayRoute(groveId, UUID.randomUUID(), "127.0.0.1", 22, "FLOURISHING");
        mockServer.expect(requestTo("/api/gateway/authorize-owner"))
                .andExpect(method(POST))
                .andExpect(content().json("{\"groveId\":\"" + groveId + "\",\"email\":\"alice@example.com\"}"))
                .andRespond(withSuccess(objectMapper.writeValueAsString(expected), MediaType.APPLICATION_JSON));

        assertThat(client.authorizeOwner(groveId, "alice@example.com")).contains(expected);
    }

    @Test
    void authorizeOwner_notOwnerOrUnknown_returnsEmpty() {
        mockServer.expect(requestTo("/api/gateway/authorize-owner"))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThat(client.authorizeOwner(UUID.randomUUID(), "bob@example.com")).isEmpty();
    }
```

(Add the missing static imports as needed: `org.springframework.test.web.client.match.MockRestRequestMatchers.method`, `.requestTo`, `org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess`/`.withStatus`, `org.springframework.http.HttpMethod.POST`, `org.springframework.http.HttpStatus`, and AssertJ `assertThat`.)

- [ ] **Step 7: Run the gateway tests**

Run: `./gradlew :gateway:test`
Expected: PASS — `OwnerTokenAuthenticatorTest` (7), `OwnerTokenAuthConfigTest` (1), `TrellisApiClientTest` (incl. the 2 new cases), plus all Phase 3 tests still green (notably `GroveRelayServerTest`'s `contextLoadsAndSshListenerIsUp`).

- [ ] **Step 8: Commit**

```bash
git add gateway/build.gradle.kts gateway/src/main/java/dev/orchard/gateway gateway/src/test/java/dev/orchard/gateway
git commit -m "feat(gateway): owner-token password auth against fence JWKS"
```

---

### Task 2: Gateway full-module verification

- [ ] **Step 1: Build the gateway**

Run: `./gradlew :gateway:build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Confirm no web-security behavior change**

Check `git diff gateway/build.gradle.kts` shows only the `spring-security-oauth2-jose` line (decision 1 — no resource-server starter, no `SecurityFilterChain` added). The admin/actuator port (8081) stays Phase 3's behavior.

- [ ] **Step 3: Commit any leftover changes**

```bash
git status
git commit -m "chore(gateway): verify owner-token auth build" || true
```

---

### Task 3: Trowel — `OrchardClient` ssh-key methods

**Files:**
- Modify: `trowel/src/main/java/dev/orchard/trowel/client/OrchardClient.java`
- Modify: `trowel/src/test/java/dev/orchard/trowel/client/OrchardClientTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `trowel/src/test/java/dev/orchard/trowel/client/OrchardClientTest.java`, following its existing `com.sun.net.httpserver.HttpServer` pattern (ephemeral port, `NoAuthProvider`):

```java
    @Test
    void registerSshPublicKey_postsAndParsesResponse() throws Exception {
        SshPublicKeyResponse expected = new SshPublicKeyResponse(
                UUID.randomUUID(), "laptop", "ssh-ed25519 AAAA", "SHA256:abc", "2026-08-03T00:00:00Z");
        String json = jsonMapper.writeValueAsString(expected);
        withHandler(exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/api/ssh-keys");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains("\"name\":\"laptop\"");
            assertThat(body).contains("\"publicKey\":\"ssh-ed25519 AAAA\"");
            byte[] resp = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });

        OrchardClient client = new OrchardClient(serverUrl(), new NoAuthProvider());
        SshPublicKeyResponse result = client.registerSshPublicKey("laptop", "ssh-ed25519 AAAA");

        assertThat(result.id()).isEqualTo(expected.id());
        assertThat(result.fingerprint()).isEqualTo("SHA256:abc");
    }

    @Test
    void listSshPublicKeys_returnsKeys() throws Exception {
        withHandler(exchange -> {
            byte[] resp = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });

        List<SshPublicKeyResponse> keys = new OrchardClient(serverUrl(), new NoAuthProvider()).listSshPublicKeys();

        assertThat(keys).isEmpty();
    }

    @Test
    void deleteSshPublicKey_sendsDelete() throws Exception {
        UUID keyId = UUID.randomUUID();
        withHandler(exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("DELETE");
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/api/ssh-keys/" + keyId);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        new OrchardClient(serverUrl(), new NoAuthProvider()).deleteSshPublicKey(keyId);
    }
```

The helpers (`serverUrl()`, `withHandler(...)`, `jsonMapper`) already exist in this test class; reuse them rather than duplicating.

Run: `./gradlew :trowel:test --tests "dev.orchard.trowel.client.OrchardClientTest"`
Expected: FAIL — `cannot find symbol: registerSshPublicKey` / `SshPublicKeyResponse` / `listSshPublicKeys` / `deleteSshPublicKey`.

- [ ] **Step 2: Implement the client methods**

Modify `trowel/src/main/java/dev/orchard/trowel/client/OrchardClient.java`. Add three methods (following the existing `authenticated(...)` + `checkResponse(...)` idiom) and two records (near the other request/response records):

```java
    public SshPublicKeyResponse registerSshPublicKey(String name, String publicKey)
            throws IOException, InterruptedException {
        String body = objectMapper.writeValueAsString(new CreateSshPublicKeyRequest(name, publicKey));

        HttpRequest httpRequest = authenticated(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/ssh-keys"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        ).build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), SshPublicKeyResponse.class);
    }

    public List<SshPublicKeyResponse> listSshPublicKeys() throws IOException, InterruptedException {
        HttpRequest request = authenticated(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/ssh-keys"))
                .GET()
        ).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), new TypeReference<List<SshPublicKeyResponse>>() {});
    }

    public void deleteSshPublicKey(UUID keyId) throws IOException, InterruptedException {
        HttpRequest request = authenticated(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/ssh-keys/" + keyId))
                .DELETE()
        ).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
    }
```

```java
    public record CreateSshPublicKeyRequest(String name, String publicKey) {}

    public record SshPublicKeyResponse(
        UUID id,
        String name,
        String publicKey,
        String fingerprint,
        String createdAt
    ) {}
```

- [ ] **Step 3: Run the tests**

Run: `./gradlew :trowel:test --tests "dev.orchard.trowel.client.OrchardClientTest"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add trowel/src/main/java/dev/orchard/trowel/client/OrchardClient.java trowel/src/test/java/dev/orchard/trowel/client/OrchardClientTest.java
git commit -m "feat(trowel): ssh-key API client methods"
```

---

### Task 4: Trowel — local SSH keypair store

**Files:**
- Modify: `trowel/build.gradle.kts`
- Create: `trowel/src/main/java/dev/orchard/trowel/ssh/SshKeyPaths.java`
- Create: `trowel/src/main/java/dev/orchard/trowel/ssh/SshKeyStore.java`
- Create: `trowel/src/test/java/dev/orchard/trowel/ssh/SshKeyStoreTest.java`

- [ ] **Step 1: Add the dependency and write the failing tests**

In `trowel/build.gradle.kts`, add to `dependencies` (same version the gateway pins):

```kotlin
    implementation("org.apache.sshd:sshd-core:2.19.0")
```

Create `trowel/src/test/java/dev/orchard/trowel/ssh/SshKeyStoreTest.java` — uses the `@TempDir` + `user.home` redirect pattern from `ConfigLoaderTest`:

```java
package dev.orchard.trowel.ssh;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;

class SshKeyStoreTest {

    @TempDir
    Path tempDir;

    private String originalHome;

    @BeforeEach
    void setUp() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalHome);
    }

    @Test
    void loadOrCreate_generatesKeypairAndWritesFiles() throws Exception {
        KeyPair pair = SshKeyStore.loadOrCreate("default");

        assertThat(pair.getPrivate()).isNotNull();
        assertThat(SshKeyPaths.privateKey("default")).exists();
        assertThat(SshKeyPaths.publicKey("default")).exists();
        assertThat(Files.readString(SshKeyPaths.publicKey("default"))).startsWith("ssh-ed25519 ");
        assertThat(Files.getPosixFilePermissions(SshKeyPaths.privateKey("default")))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void loadOrCreate_isIdempotent() throws Exception {
        KeyPair first = SshKeyStore.loadOrCreate("default");
        KeyPair second = SshKeyStore.loadOrCreate("default");

        assertThat(second.getPublic()).isEqualTo(first.getPublic());
    }

    @Test
    void load_readsBackWrittenKey() throws Exception {
        KeyPair created = SshKeyStore.loadOrCreate("default");
        KeyPair loaded = SshKeyStore.load("default");

        assertThat(loaded.getPublic()).isEqualTo(created.getPublic());
    }

    @Test
    void publicLine_isFingerprintable() throws Exception {
        SshKeyStore.loadOrCreate("default");
        String publicLine = Files.readString(SshKeyPaths.publicKey("default")).trim();

        assertThat(dev.orchard.core.model.SshPublicKey.fingerprint(publicLine)).startsWith("SHA256:");
    }
}
```

Run: `./gradlew :trowel:test --tests "dev.orchard.trowel.ssh.*"`
Expected: FAIL — `cannot find symbol: class SshKeyStore` / `SshKeyPaths`.

- [ ] **Step 2: Create `SshKeyPaths`**

Create `trowel/src/main/java/dev/orchard/trowel/ssh/SshKeyPaths.java`:

```java
package dev.orchard.trowel.ssh;

import java.nio.file.Path;

/** Well-known paths for trowel-managed SSH keys, under ~/.orchard/keys. */
public final class SshKeyPaths {

    private SshKeyPaths() {}

    public static Path keysDir() {
        return Path.of(System.getProperty("user.home"), ".orchard", "keys");
    }

    public static Path privateKey(String name) {
        return keysDir().resolve(name);
    }

    public static Path publicKey(String name) {
        return keysDir().resolve(name + ".pub");
    }
}
```

- [ ] **Step 3: Create `SshKeyStore`**

Create `trowel/src/main/java/dev/orchard/trowel/ssh/SshKeyStore.java`. Uses JCA `Ed25519` generation and the sshd OpenSSH writer/parser (the exact APIs the gateway module uses):

```java
package dev.orchard.trowel.ssh;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.loader.openssh.OpenSSHKeyPairResourceParser;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

/**
 * Generates, persists, and loads trowel-managed SSH keypairs. The private key
 * is written in OpenSSH format (interoperable with plain {@code ssh -i}) and
 * locked down to owner-only; the public line is the standard one-line format
 * registered with trellis via {@code ssh-key add}.
 */
public final class SshKeyStore {

    private SshKeyStore() {}

    public static KeyPair loadOrCreate(String name) throws Exception {
        if (Files.exists(SshKeyPaths.privateKey(name))) {
            return load(name);
        }
        Files.createDirectories(SshKeyPaths.keysDir());
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        write(keyPair, name);
        return keyPair;
    }

    public static KeyPair load(String name) throws Exception {
        return OpenSSHKeyPairResourceParser.INSTANCE
                .loadKeyPairs(null, SshKeyPaths.privateKey(name), null)
                .iterator().next();
    }

    public static void write(KeyPair keyPair, String name) throws IOException {
        Path privatePath = SshKeyPaths.privateKey(name);
        Files.createDirectories(privatePath.getParent());
        try (var out = Files.newOutputStream(privatePath)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, name + "@orchard", null, out);
        }
        Files.setPosixFilePermissions(privatePath, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(SshKeyPaths.publicKey(name), PublicKeyEntry.toString(keyPair.getPublic()) + "\n");
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :trowel:test --tests "dev.orchard.trowel.ssh.*"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add trowel/build.gradle.kts trowel/src/main/java/dev/orchard/trowel/ssh trowel/src/test/java/dev/orchard/trowel/ssh
git commit -m "feat(trowel): local OpenSSH keypair store under ~/.orchard/keys"
```

---

### Task 5: Trowel — `SshKeyCommand` group

**Files:**
- Modify: `trowel/src/main/java/dev/orchard/trowel/Trowel.java`
- Create: `trowel/src/main/java/dev/orchard/trowel/command/SshKeyCommand.java`
- Create: `trowel/src/test/java/dev/orchard/trowel/command/SshKeyCommandTest.java`

- [ ] **Step 1: Write the failing tests**

Create `trowel/src/test/java/dev/orchard/trowel/command/SshKeyCommandTest.java`. Combines the `DevServerCommandTest` harness (temp `user.home`, System.out capture, `new CommandLine(new Trowel())`) with a `com.sun.net.httpserver.HttpServer` stubbing `/api/ssh-keys` (the `OrchardClientTest` approach). `Trowel.getAuthProvider()` returns `NoAuthProvider` when no tokens are configured, so no auth header is sent — fine against the stub.

```java
package dev.orchard.trowel.command;

import com.sun.net.httpserver.HttpServer;
import dev.orchard.trowel.Trowel;
import dev.orchard.trowel.ssh.SshKeyPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class SshKeyCommandTest {

    @TempDir
    Path tempDir;

    private String originalHome;
    private final PrintStream originalOut = System.out;
    private HttpServer server;
    private final List<String> hits = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/ssh-keys", exchange -> {
            hits.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            byte[] resp;
            int status;
            switch (exchange.getRequestMethod()) {
                case "POST" -> {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    hits.add(body);
                    resp = ("{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"default\","
                            + "\"publicKey\":\"ssh-ed25519 AAAA\",\"fingerprint\":\"SHA256:test\","
                            + "\"createdAt\":\"2026-08-03T00:00:00Z\"}").getBytes(StandardCharsets.UTF_8);
                    status = 201;
                }
                case "GET" -> {
                    resp = "[]".getBytes(StandardCharsets.UTF_8);
                    status = 200;
                }
                default -> {
                    resp = new byte[0];
                    status = 204;
                }
            }
            exchange.sendResponseHeaders(status, resp.length == 0 ? -1 : resp.length);
            if (resp.length > 0) {
                exchange.getResponseBody().write(resp);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        System.setProperty("user.home", originalHome);
        System.setOut(originalOut);
    }

    private String run(String... args) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        int exit = new CommandLine(new Trowel()).execute(args);
        System.setOut(originalOut);
        assertThat(exit).isZero();
        return buf.toString(StandardCharsets.UTF_8);
    }

    private String serverUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    @Test
    void add_generatesLocalKeyAndRegistersPublicHalf() {
        String out = run("ssh-key", "add", "--server", serverUrl());

        assertThat(out).contains("Registered SSH key");
        assertThat(SshKeyPaths.privateKey("default")).exists();
        assertThat(SshKeyPaths.publicKey("default")).exists();
        assertThat(hits).anyMatch(h -> h.startsWith("POST /api/ssh-keys"));
        assertThat(hits).anyMatch(h -> h.contains("\"publicKey\":\"ssh-ed25519 "));
    }

    @Test
    void list_printsEmptyMessageWhenNoKeys() {
        String out = run("ssh-key", "list", "--server", serverUrl());

        assertThat(out).contains("No SSH keys registered");
        assertThat(hits).contains("GET /api/ssh-keys");
    }

    @Test
    void remove_deletesKey() {
        UUID keyId = UUID.randomUUID();
        String out = run("ssh-key", "remove", "--server", serverUrl(), keyId.toString());

        assertThat(out).contains("Removed SSH key " + keyId);
        assertThat(hits).contains("DELETE /api/ssh-keys/" + keyId);
    }
}
```

Run: `./gradlew :trowel:test --tests "dev.orchard.trowel.command.SshKeyCommandTest"`
Expected: FAIL — `cannot find symbol: class SshKeyCommand`.

- [ ] **Step 2: Create `SshKeyCommand`**

Create `trowel/src/main/java/dev/orchard/trowel/command/SshKeyCommand.java` — same shape as `GroveCommand` (`@ParentCommand` chains, subcommands construct `OrchardClient` from the `Trowel` root):

```java
package dev.orchard.trowel.command;

import dev.orchard.trowel.Trowel;
import dev.orchard.trowel.client.OrchardClient;
import dev.orchard.trowel.client.OrchardClient.SshPublicKeyResponse;
import dev.orchard.trowel.ssh.SshKeyPaths;
import dev.orchard.trowel.ssh.SshKeyStore;
import picocli.CommandLine;

import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.Callable;

@CommandLine.Command(
    name = "ssh-key",
    aliases = {"sshkeys"},
    description = "Manage SSH keys registered with Orchard",
    subcommands = {SshKeyCommand.Add.class, SshKeyCommand.List.class, SshKeyCommand.Remove.class}
)
public class SshKeyCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    Trowel parent;

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    @CommandLine.Command(name = "add", description = "Generate a local keypair if needed and register its public key")
    public static class Add implements Callable<Integer> {

        @CommandLine.ParentCommand
        SshKeyCommand parent;

        @CommandLine.Option(names = {"-n", "--name"}, description = "Key name (default: default)")
        String name = "default";

        @Override
        public Integer call() throws Exception {
            SshKeyStore.loadOrCreate(name);
            String publicLine = Files.readString(SshKeyPaths.publicKey(name)).trim();

            OrchardClient client = new OrchardClient(parent.parent.getServerUrl(), parent.parent.getAuthProvider());
            SshPublicKeyResponse created = client.registerSshPublicKey(name, publicLine);

            System.out.println("Registered SSH key '" + created.name() + "'");
            System.out.println("  fingerprint: " + created.fingerprint());
            System.out.println("  key file:    " + SshKeyPaths.privateKey(name));
            return 0;
        }
    }

    @CommandLine.Command(name = "list", aliases = {"ls"}, description = "List registered SSH keys")
    public static class List implements Callable<Integer> {

        @CommandLine.ParentCommand
        SshKeyCommand parent;

        @Override
        public Integer call() throws Exception {
            OrchardClient client = new OrchardClient(parent.parent.getServerUrl(), parent.parent.getAuthProvider());
            var keys = client.listSshPublicKeys();

            if (keys.isEmpty()) {
                System.out.println("No SSH keys registered. Use 'ssh-key add' to register one.");
                return 0;
            }
            for (SshPublicKeyResponse key : keys) {
                System.out.printf("%s  %-20s %s%n", key.id(), key.name(), key.fingerprint());
            }
            return 0;
        }
    }

    @CommandLine.Command(name = "remove", aliases = {"rm"}, description = "Remove a registered SSH key")
    public static class Remove implements Callable<Integer> {

        @CommandLine.ParentCommand
        SshKeyCommand parent;

        @CommandLine.Parameters(index = "0", description = "Key id to remove")
        UUID keyId;

        @Override
        public Integer call() throws Exception {
            OrchardClient client = new OrchardClient(parent.parent.getServerUrl(), parent.parent.getAuthProvider());
            client.deleteSshPublicKey(keyId);
            System.out.println("Removed SSH key " + keyId);
            return 0;
        }
    }
}
```

Note: `CommandLine.usage(this, System.out)` is the established group-command pattern (`BeeCommand.call()` does exactly this).

- [ ] **Step 3: Register the command**

Modify `trowel/src/main/java/dev/orchard/trowel/Trowel.java`, adding `SshKeyCommand.class` to the `subcommands` list (after `DevServerCommand.class`):

```java
        DevServerCommand.class,
        SshKeyCommand.class,
        LoginCommand.class,
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :trowel:test --tests "dev.orchard.trowel.command.SshKeyCommandTest"`
Expected: PASS (3 tests). Also run `--tests "dev.orchard.trowel.command.GroveCommandTest"` and `--tests "dev.orchard.trowel.ssh.*"` to confirm no regressions.

- [ ] **Step 5: Commit**

```bash
git add trowel/src/main/java/dev/orchard/trowel/Trowel.java trowel/src/main/java/dev/orchard/trowel/command/SshKeyCommand.java trowel/src/test/java/dev/orchard/trowel/command/SshKeyCommandTest.java
git commit -m "feat(trowel): ssh-key add/list/remove command"
```

---

### Task 6: Full build + docs

- [ ] **Step 1: Run the full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — all modules green (core, roots, harvest, nursery, greenhouse, apiary, trellis, trowel, fence, gateway, integration-tests).

- [ ] **Step 2: Manual smoke (optional, with a running stack)**

With the stack up per Phase 3 Task 9 Step 2 (postgres, trellis, fence standalone with the Phase 2 gateway client configured, gateway running on 2222), plus a logged-in trowel target and a FLOURISHING grove owned by the logged-in cultivator:

```bash
./gradlew :trowel:run --args="ssh-key add"            # generates ~/.orchard/keys/default and registers it
./gradlew :trowel:run --args="ssh-key list"
```

Then from another shell, publickey auth (Phase 3 path):

```bash
ssh -o StrictHostKeyChecking=no -p 2222 <grove-uuid>@localhost -i ~/.orchard/keys/default
```

- [ ] **Step 3: Confirm no stray files**

Run: `git status`
Expected: working tree clean (all changes committed in Tasks 1–5).

- [ ] **Step 4: Update `docs/TOC.md`** if it enumerates the CLI command tree

Check whether `docs/TOC.md` lists trowel subcommands; if so, add `ssh-key add|list|remove`.

---

## Checkpoints

- **After Task 1:** gateway module has owner-token auth (`OwnerTokenAuthenticator` installed on the MINA server), all gateway tests green.
- **After Task 3:** `OrchardClient` can register/list/delete keys; `OrchardClientTest` green.
- **After Task 4:** local keypair store round-trips; `SshKeyStoreTest` green.
- **After Task 5:** `trowel ssh-key add|list|remove` works end-to-end against the stub; full trowel test suite green.
- **After Task 6:** `./gradlew build` is fully green; working tree clean.

---

## Out of Scope (this phase)

- Fence `POST /gateway-token` (Phase 2) and any change to `TokenClaimsConfig` / `SigningKeyConfig` (scope-4 constraint).
- A trowel command to *fetch* an owner token (not in scope-4/5; user pastes the token from any OAuth client).
- Rewiring `grove connect` / `Grove.getSshConnectionString()` / `GroveResponse.sshConnectionString` to the gateway or `~/.orchard/keys` (external contract; Phase 3 plan + spec Out-of-Scope).
- Gateway auto-start or management from `DevServerCommand`.
- Any change to `OrchardConfig` / `OrchardConfig.Target` / `ConfigLoader` TOML schema.
- Adding Spring Security web/HTTP-layer JWT protection to the gateway (decision 1).
- Interactive-pty fidelity, multi-grove-per-connection routing, or any `roots`/JPA dependency in the gateway.
