# Grove SSH Gateway — Trellis Gateway API Implementation Plan (Phase 1 of 4)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the trellis-side API surface the SSH gateway depends on: routable-grove routing, key listing, and owner-authorization endpoints under `/api/gateway/**`, a public `SshPublicKey.fingerprint` for the gateway's key authenticator, `CultivatorService.findByEmail`, and the `CultivatorAuthFilter` email-claim guard so `client_credentials` service tokens pass through without cultivator resolution.

**Architecture:** Phase 1 of 4 (see `docs/superpowers/specs/2026-08-03-grove-ssh-gateway-design.md`). Adds a thin `GatewayGroveService` orchestrating existing `GroveService`/`SshPublicKeyService`/`CultivatorService`, a `GatewayGroveController` under `/api/gateway/**` protected by the existing `oauth2ResourceServer` chain, and a filter guard. No new Gradle module in this phase.

**Tech Stack:** Java 25, Spring Boot 4.1 (`@WebMvcTest`, `@MockitoBean`), Spring Data JPA, JUnit 5, AssertJ, Mockito, MockMvc.

---

### Task 1: Expose `SshPublicKey.fingerprint` as a public API

The gateway's `KeyAuthenticator` (Phase 3) must compute the fingerprint of an offered SSH key to match against registered fingerprints — using the exact same algorithm as registration. Today `SshPublicKey.fingerprint(String)` is package-private. Make it public static.

**Files:**
- Modify: `core/src/main/java/dev/orchard/core/model/SshPublicKey.java:37`
- Test: `trellis/src/test/java/dev/orchard/api/gateway/SshPublicKeyFingerprintContractTest.java` (new)

- [ ] **Step 1: Write the failing contract test** (in trellis, a different package than `dev.orchard.core.model` — it must not compile until the method is public)

Create `trellis/src/test/java/dev/orchard/api/gateway/SshPublicKeyFingerprintContractTest.java`:

```java
package dev.orchard.api.gateway;

import dev.orchard.core.model.SshPublicKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: the SSH gateway must fingerprint offered keys with the same
 * algorithm as key registration. Lives outside dev.orchard.core.model to prove
 * SshPublicKey.fingerprint is public API (the gateway module calls it).
 */
class SshPublicKeyFingerprintContractTest {

    private static final String ED25519_KEY =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINu3uCEBEcYqqjErEWRewbGZw8qrfWt/0inp+HfZR7MR test@orchard.dev";
    private static final String ED25519_FINGERPRINT = "SHA256:5h8EgYzAsG8Fzte4Vy+j+E9CN2lHRyowxZlFhnUA2Rc";

    @Test
    void fingerprint_isCallableFromOtherPackages() {
        assertThat(SshPublicKey.fingerprint(ED25519_KEY)).isEqualTo(ED25519_FINGERPRINT);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails (compile error)**

Run: `./gradlew :trellis:test --tests "dev.orchard.api.gateway.*"`
Expected: FAIL — test compilation error:
`error: fingerprint(String) is not public in SshPublicKey; cannot be accessed from outside package`

- [ ] **Step 3: Make the method public**

Edit `core/src/main/java/dev/orchard/core/model/SshPublicKey.java:37`:

```java
    public static String fingerprint(String publicKey) {
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :trellis:test --tests "dev.orchard.api.gateway.*"`
Expected: PASS (1 test)

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/dev/orchard/core/model/SshPublicKey.java trellis/src/test/java/dev/orchard/api/gateway/SshPublicKeyFingerprintContractTest.java
git commit -m "feat(core): expose SshPublicKey.fingerprint as public API for SSH gateway"
```

---

### Task 2: Add `CultivatorService.findByEmail`

`CultivatorRepository.findByEmail(String)` already exists (roots). Add the service-level mapping used by the gateway's owner-authorization flow.

**Files:**
- Modify: `trellis/src/main/java/dev/orchard/api/service/CultivatorService.java` (after `findByUsername`)
- Test: `trellis/src/test/java/dev/orchard/api/service/CultivatorServiceTest.java`

- [ ] **Step 1: Write the failing tests** — append to `CultivatorServiceTest` (it uses `@ExtendWith(MockitoExtension.class)` + `@Mock CultivatorRepository`):

```java
    @Test
    void findByEmail_returnsMappedCultivator() {
        CultivatorEntity entity = new CultivatorEntity(
            UUID.randomUUID(), "alice", "alice@example.com", "google", "goog-1",
            null, null, Instant.now(), Instant.now()
        );
        when(cultivatorRepository.findByEmail("alice@example.com"))
            .thenReturn(Optional.of(entity));

        Optional<Cultivator> result = cultivatorService.findByEmail("alice@example.com");

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(entity.getId());
        assertThat(result.get().email()).isEqualTo("alice@example.com");
    }

    @Test
    void findByEmail_returnsEmptyWhenUnknown() {
        when(cultivatorRepository.findByEmail("nobody@example.com"))
            .thenReturn(Optional.empty());

        assertThat(cultivatorService.findByEmail("nobody@example.com")).isEmpty();
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :trellis:test --tests "dev.orchard.api.service.CultivatorServiceTest"`
Expected: FAIL — `cannot find symbol: method findByEmail(String)`

- [ ] **Step 3: Implement `findByEmail`** — add to `CultivatorService` after the `findByUsername` method:

```java
    /**
     * Find a cultivator by email.
     */
    public Optional<Cultivator> findByEmail(String email) {
        return cultivatorRepository.findByEmail(email)
            .map(CultivatorEntity::toModel);
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :trellis:test --tests "dev.orchard.api.service.CultivatorServiceTest"`
Expected: PASS (existing + 2 new tests)

- [ ] **Step 5: Commit**

```bash
git add trellis/src/main/java/dev/orchard/api/service/CultivatorService.java trellis/src/test/java/dev/orchard/api/service/CultivatorServiceTest.java
git commit -m "feat(trellis): add CultivatorService.findByEmail for gateway owner check"
```

---

### Task 3: Add gateway DTOs and `GatewayGroveService`

**Files:**
- Create: `trellis/src/main/java/dev/orchard/api/dto/GatewayGroveResponse.java`
- Create: `trellis/src/main/java/dev/orchard/api/dto/GatewayKeyResponse.java`
- Create: `trellis/src/main/java/dev/orchard/api/dto/AuthorizeOwnerRequest.java`
- Create: `trellis/src/main/java/dev/orchard/api/service/GatewayGroveService.java`
- Test: `trellis/src/test/java/dev/orchard/api/service/GatewayGroveServiceTest.java`

- [ ] **Step 1: Write the failing service tests**

Create `trellis/src/test/java/dev/orchard/api/service/GatewayGroveServiceTest.java`:

```java
package dev.orchard.api.service;

import dev.orchard.core.model.Cultivator;
import dev.orchard.core.model.Grove;
import dev.orchard.core.model.GroveState;
import dev.orchard.core.model.SeedSpec;
import dev.orchard.core.model.Seedling;
import dev.orchard.core.model.SeedlingState;
import dev.orchard.core.model.SshPublicKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GatewayGroveServiceTest {

    private static final String KEY =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINu3uCEBEcYqqjErEWRewbGZw8qrfWt/0inp+HfZR7MR test@orchard.dev";

    @Mock private GroveService groveService;
    @Mock private SshPublicKeyService sshPublicKeyService;
    @Mock private CultivatorService cultivatorService;

    private GatewayGroveService service;

    @BeforeEach
    void setUp() {
        service = new GatewayGroveService(groveService, sshPublicKeyService, cultivatorService);
    }

    private static Grove routableGrove(UUID groveId, UUID cultivatorId) {
        Seedling seedling = new Seedling(
            UUID.randomUUID(), groveId, "inst-1", "127.0.0.1", 22,
            SeedlingState.SAPLING, Seedling.SeedlingSpec.small(), Instant.now(), Instant.now()
        );
        // Grove.plant() always mints its own random id, which would leave this grove's id
        // different from groveId (what the test queries getGrove(groveId) with) — construct
        // the record directly so route.get().groveId() actually equals groveId.
        return new Grove(
            groveId, cultivatorId, "demo", "https://github.com/org/demo", "main", null,
            GroveState.FLOURISHING, SeedSpec.AUTO, seedling, List.of(), Instant.now(), Instant.now()
        );
    }

    private static Cultivator cultivator(UUID id, String email) {
        return new Cultivator(id, email, email, "google", "goog-" + id, null, null, Instant.now(), Instant.now());
    }

    @Test
    void resolveRoute_returnsRouteForRoutableGrove() {
        UUID groveId = UUID.randomUUID();
        UUID cultivatorId = UUID.randomUUID();
        when(groveService.getGrove(groveId)).thenReturn(Optional.of(routableGrove(groveId, cultivatorId)));

        var route = service.resolveRoute(groveId);

        assertThat(route).isPresent();
        assertThat(route.get().groveId()).isEqualTo(groveId);
        assertThat(route.get().cultivatorId()).isEqualTo(cultivatorId);
        assertThat(route.get().seedlingIp()).isEqualTo("127.0.0.1");
        assertThat(route.get().seedlingPort()).isEqualTo(22);
        assertThat(route.get().state()).isEqualTo("FLOURISHING");
    }

    @Test
    void resolveRoute_emptyForUnknownGrove() {
        UUID groveId = UUID.randomUUID();
        when(groveService.getGrove(groveId)).thenReturn(Optional.empty());

        assertThat(service.resolveRoute(groveId)).isEmpty();
    }

    @Test
    void resolveRoute_emptyWhenSeedlingMissing() {
        UUID cultivatorId = UUID.randomUUID();
        Grove grove = Grove.plant(cultivatorId, "demo", "https://github.com/org/demo", "main")
            .withState(GroveState.PLANTING);
        when(groveService.getGrove(grove.id())).thenReturn(Optional.of(grove));

        assertThat(service.resolveRoute(grove.id())).isEmpty();
    }

    @Test
    void resolveRoute_emptyWhenSeedlingNotSapLing() {
        UUID groveId = UUID.randomUUID();
        UUID cultivatorId = UUID.randomUUID();
        Seedling germinating = Seedling.germinate(groveId, Seedling.SeedlingSpec.small());
        Grove grove = Grove.plant(cultivatorId, "demo", "https://github.com/org/demo", "main")
            .withSeedling(germinating)
            .withState(GroveState.GROWING);
        when(groveService.getGrove(groveId)).thenReturn(Optional.of(grove));

        assertThat(service.resolveRoute(groveId)).isEmpty();
    }

    @Test
    void exists_delegatesToGroveService() {
        UUID groveId = UUID.randomUUID();
        when(groveService.getGrove(groveId)).thenReturn(Optional.empty());
        assertThat(service.exists(groveId)).isFalse();

        when(groveService.getGrove(groveId))
            .thenReturn(Optional.of(routableGrove(groveId, UUID.randomUUID())));
        assertThat(service.exists(groveId)).isTrue();
    }

    @Test
    void listKeys_delegatesAndMaps() {
        UUID cultivatorId = UUID.randomUUID();
        SshPublicKey key = SshPublicKey.register(cultivatorId, "laptop", KEY);
        when(sshPublicKeyService.listForCultivator(cultivatorId)).thenReturn(List.of(key));

        var keys = service.listKeys(cultivatorId);

        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).id()).isEqualTo(key.id());
        assertThat(keys.get(0).name()).isEqualTo("laptop");
        assertThat(keys.get(0).publicKey()).isEqualTo(KEY);
        assertThat(keys.get(0).fingerprint()).isEqualTo(key.fingerprint());
    }

    @Test
    void authorizeOwner_allowsOwnerEmail() {
        UUID groveId = UUID.randomUUID();
        UUID cultivatorId = UUID.randomUUID();
        when(groveService.getGrove(groveId)).thenReturn(Optional.of(routableGrove(groveId, cultivatorId)));
        when(cultivatorService.findByEmail("alice@example.com"))
            .thenReturn(Optional.of(cultivator(cultivatorId, "alice@example.com")));

        var route = service.authorizeOwner(groveId, "alice@example.com");

        assertThat(route).isPresent();
        assertThat(route.get().groveId()).isEqualTo(groveId);
    }

    @Test
    void authorizeOwner_rejectsUnknownEmail() {
        UUID groveId = UUID.randomUUID();
        UUID cultivatorId = UUID.randomUUID();
        when(groveService.getGrove(groveId)).thenReturn(Optional.of(routableGrove(groveId, cultivatorId)));
        when(cultivatorService.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThat(service.authorizeOwner(groveId, "nobody@example.com")).isEmpty();
    }

    @Test
    void authorizeOwner_rejectsNonOwnerEmail() {
        UUID groveId = UUID.randomUUID();
        UUID cultivatorId = UUID.randomUUID();
        when(groveService.getGrove(groveId)).thenReturn(Optional.of(routableGrove(groveId, cultivatorId)));
        when(cultivatorService.findByEmail("bob@example.com"))
            .thenReturn(Optional.of(cultivator(UUID.randomUUID(), "bob@example.com")));

        assertThat(service.authorizeOwner(groveId, "bob@example.com")).isEmpty();
    }

    @Test
    void authorizeOwner_rejectsNotRoutableGrove() {
        UUID groveId = UUID.randomUUID();
        UUID cultivatorId = UUID.randomUUID();
        Grove grove = Grove.plant(cultivatorId, "demo", "https://github.com/org/demo", "main")
            .withState(GroveState.PLANTING);
        when(groveService.getGrove(groveId)).thenReturn(Optional.of(grove));

        assertThat(service.authorizeOwner(groveId, "alice@example.com")).isEmpty();
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :trellis:test --tests "dev.orchard.api.service.GatewayGroveServiceTest"`
Expected: FAIL — `cannot find symbol: class GatewayGroveService` (and missing DTO classes)

- [ ] **Step 3: Create the DTOs**

Create `trellis/src/main/java/dev/orchard/api/dto/GatewayGroveResponse.java`:

```java
package dev.orchard.api.dto;

import java.util.UUID;

/** Route info the SSH gateway needs to reach a grove's seedling. */
public record GatewayGroveResponse(
    UUID groveId,
    UUID cultivatorId,
    String seedlingIp,
    int seedlingPort,
    String state
) {}
```

Create `trellis/src/main/java/dev/orchard/api/dto/GatewayKeyResponse.java`:

```java
package dev.orchard.api.dto;

import java.util.UUID;

/** A cultivator's registered SSH public key, as seen by the SSH gateway. */
public record GatewayKeyResponse(
    UUID id,
    String name,
    String publicKey,
    String fingerprint
) {}
```

Create `trellis/src/main/java/dev/orchard/api/dto/AuthorizeOwnerRequest.java`:

```java
package dev.orchard.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Owner-token auth: proves an email owns a grove before the gateway routes to it. */
public record AuthorizeOwnerRequest(
    @NotNull UUID groveId,
    @NotBlank @Email String email
) {}
```

- [ ] **Step 4: Implement `GatewayGroveService`**

Create `trellis/src/main/java/dev/orchard/api/service/GatewayGroveService.java`:

```java
package dev.orchard.api.service;

import dev.orchard.api.dto.GatewayGroveResponse;
import dev.orchard.api.dto.GatewayKeyResponse;
import dev.orchard.core.model.Cultivator;
import dev.orchard.core.model.Grove;
import dev.orchard.core.model.SeedlingState;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Internal API consumed by the SSH gateway (dev.orchard.gateway). A grove is
 * routable only when its seedling is running (SAPLING) with an IP/port.
 */
@Service
public class GatewayGroveService {

    private final GroveService groveService;
    private final SshPublicKeyService sshPublicKeyService;
    private final CultivatorService cultivatorService;

    public GatewayGroveService(GroveService groveService, SshPublicKeyService sshPublicKeyService,
                               CultivatorService cultivatorService) {
        this.groveService = groveService;
        this.sshPublicKeyService = sshPublicKeyService;
        this.cultivatorService = cultivatorService;
    }

    /** Returns the route for a grove whose seedling is running (routable), if any. */
    public Optional<GatewayGroveResponse> resolveRoute(UUID groveId) {
        return groveService.getGrove(groveId)
            .filter(this::isRoutable)
            .map(this::toResponse);
    }

    /** True when a grove with the given id exists (regardless of readiness). */
    public boolean exists(UUID groveId) {
        return groveService.getGrove(groveId).isPresent();
    }

    /** Registered SSH public keys for a cultivator, for gateway key matching. */
    public List<GatewayKeyResponse> listKeys(UUID cultivatorId) {
        return sshPublicKeyService.listForCultivator(cultivatorId).stream()
            .map(k -> new GatewayKeyResponse(k.id(), k.name(), k.publicKey(), k.fingerprint()))
            .toList();
    }

    /**
     * Returns the route only when the grove is routable AND owned by the
     * cultivator identified by {@code email}.
     */
    public Optional<GatewayGroveResponse> authorizeOwner(UUID groveId, String email) {
        Optional<GatewayGroveResponse> route = resolveRoute(groveId);
        if (route.isEmpty()) {
            return Optional.empty();
        }
        Optional<Cultivator> owner = cultivatorService.findByEmail(email);
        if (owner.isEmpty() || !owner.get().id().equals(route.get().cultivatorId())) {
            return Optional.empty();
        }
        return route;
    }

    private boolean isRoutable(Grove grove) {
        return grove.seedling() != null
            && grove.seedling().ipAddress() != null
            && grove.seedling().state() == SeedlingState.SAPLING;
    }

    private GatewayGroveResponse toResponse(Grove grove) {
        var seedling = grove.seedling();
        return new GatewayGroveResponse(
            grove.id(),
            grove.cultivatorId(),
            seedling.ipAddress(),
            seedling.sshPort(),
            grove.state().name()
        );
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :trellis:test --tests "dev.orchard.api.service.GatewayGroveServiceTest"`
Expected: PASS (all 11 tests)

- [ ] **Step 6: Commit**

```bash
git add trellis/src/main/java/dev/orchard/api/dto/GatewayGroveResponse.java trellis/src/main/java/dev/orchard/api/dto/GatewayKeyResponse.java trellis/src/main/java/dev/orchard/api/dto/AuthorizeOwnerRequest.java trellis/src/main/java/dev/orchard/api/service/GatewayGroveService.java trellis/src/test/java/dev/orchard/api/service/GatewayGroveServiceTest.java
git commit -m "feat(trellis): add GatewayGroveService for SSH gateway routing and owner check"
```

---

### Task 4: Add `GatewayGroveController`

**Files:**
- Create: `trellis/src/main/java/dev/orchard/api/controller/GatewayGroveController.java`
- Test: `trellis/src/test/java/dev/orchard/api/controller/GatewayGroveControllerTest.java`

- [ ] **Step 1: Write the failing controller tests**

Create `trellis/src/test/java/dev/orchard/api/controller/GatewayGroveControllerTest.java`:

```java
package dev.orchard.api.controller;

import dev.orchard.api.dto.GatewayGroveResponse;
import dev.orchard.api.dto.GatewayKeyResponse;
import dev.orchard.api.service.GatewayGroveService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GatewayGroveController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GatewayGroveController.class, GlobalExceptionHandler.class})
class GatewayGroveControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GatewayGroveService service;

    @Test
    void resolveGrove_routable_returns200() throws Exception {
        UUID groveId = UUID.randomUUID();
        when(service.resolveRoute(groveId)).thenReturn(Optional.of(
            new GatewayGroveResponse(groveId, UUID.randomUUID(), "127.0.0.1", 22, "FLOURISHING")));

        mockMvc.perform(get("/api/gateway/groves/{groveId}", groveId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.groveId").value(groveId.toString()))
            .andExpect(jsonPath("$.seedlingIp").value("127.0.0.1"))
            .andExpect(jsonPath("$.seedlingPort").value(22))
            .andExpect(jsonPath("$.state").value("FLOURISHING"));
    }

    @Test
    void resolveGrove_unknown_returns404() throws Exception {
        UUID groveId = UUID.randomUUID();
        when(service.resolveRoute(groveId)).thenReturn(Optional.empty());
        when(service.exists(groveId)).thenReturn(false);

        mockMvc.perform(get("/api/gateway/groves/{groveId}", groveId))
            .andExpect(status().isNotFound());
    }

    @Test
    void resolveGrove_notReady_returns409() throws Exception {
        UUID groveId = UUID.randomUUID();
        when(service.resolveRoute(groveId)).thenReturn(Optional.empty());
        when(service.exists(groveId)).thenReturn(true);

        mockMvc.perform(get("/api/gateway/groves/{groveId}", groveId))
            .andExpect(status().isConflict());
    }

    @Test
    void listKeys_returnsKeys() throws Exception {
        UUID cultivatorId = UUID.randomUUID();
        when(service.listKeys(cultivatorId)).thenReturn(List.of(
            new GatewayKeyResponse(UUID.randomUUID(), "laptop", "ssh-ed25519 AAAA test@orchard.dev", "SHA256:abc")));

        mockMvc.perform(get("/api/gateway/cultivators/{id}/keys", cultivatorId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].name").value("laptop"))
            .andExpect(jsonPath("$[0].fingerprint").value("SHA256:abc"));
    }

    @Test
    void authorizeOwner_owner_returns200() throws Exception {
        UUID groveId = UUID.randomUUID();
        when(service.authorizeOwner(groveId, "alice@example.com")).thenReturn(Optional.of(
            new GatewayGroveResponse(groveId, UUID.randomUUID(), "127.0.0.1", 22, "FLOURISHING")));

        mockMvc.perform(post("/api/gateway/authorize-owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groveId\":\"" + groveId + "\",\"email\":\"alice@example.com\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.groveId").value(groveId.toString()));
    }

    @Test
    void authorizeOwner_notOwner_returns403() throws Exception {
        UUID groveId = UUID.randomUUID();
        when(service.authorizeOwner(groveId, "bob@example.com")).thenReturn(Optional.empty());
        when(service.exists(groveId)).thenReturn(true);

        mockMvc.perform(post("/api/gateway/authorize-owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groveId\":\"" + groveId + "\",\"email\":\"bob@example.com\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void authorizeOwner_unknownGrove_returns404() throws Exception {
        UUID groveId = UUID.randomUUID();
        when(service.authorizeOwner(groveId, "alice@example.com")).thenReturn(Optional.empty());
        when(service.exists(groveId)).thenReturn(false);

        mockMvc.perform(post("/api/gateway/authorize-owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groveId\":\"" + groveId + "\",\"email\":\"alice@example.com\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void authorizeOwner_blankEmail_returns400() throws Exception {
        UUID groveId = UUID.randomUUID();

        mockMvc.perform(post("/api/gateway/authorize-owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groveId\":\"" + groveId + "\",\"email\":\"\"}"))
            .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :trellis:test --tests "dev.orchard.api.controller.GatewayGroveControllerTest"`
Expected: FAIL — `cannot find symbol: class GatewayGroveController`

- [ ] **Step 3: Implement the controller**

Create `trellis/src/main/java/dev/orchard/api/controller/GatewayGroveController.java`:

```java
package dev.orchard.api.controller;

import dev.orchard.api.dto.AuthorizeOwnerRequest;
import dev.orchard.api.dto.GatewayGroveResponse;
import dev.orchard.api.dto.GatewayKeyResponse;
import dev.orchard.api.service.GatewayGroveService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Internal API consumed by the SSH gateway (dev.orchard.gateway). Protected by
 * the standard oauth2ResourceServer chain — the gateway authenticates with a
 * client_credentials service token (see SecurityConfig).
 */
@RestController
@RequestMapping("/api/gateway")
public class GatewayGroveController {

    private final GatewayGroveService gatewayGroveService;

    public GatewayGroveController(GatewayGroveService gatewayGroveService) {
        this.gatewayGroveService = gatewayGroveService;
    }

    /**
     * Routes a grove for the gateway. 200 route, 404 unknown grove, 409 when
     * the grove exists but its seedling is not running (not routable).
     */
    @GetMapping("/groves/{groveId}")
    public ResponseEntity<GatewayGroveResponse> resolveGrove(@PathVariable UUID groveId) {
        var route = gatewayGroveService.resolveRoute(groveId);
        if (route.isPresent()) {
            return ResponseEntity.ok(route.get());
        }
        return gatewayGroveService.exists(groveId)
            ? ResponseEntity.status(HttpStatus.CONFLICT).build()
            : ResponseEntity.notFound().build();
    }

    /** Registered SSH public keys for a cultivator, for gateway key matching. */
    @GetMapping("/cultivators/{cultivatorId}/keys")
    public ResponseEntity<List<GatewayKeyResponse>> listKeys(@PathVariable UUID cultivatorId) {
        return ResponseEntity.ok(gatewayGroveService.listKeys(cultivatorId));
    }

    /**
     * Owner-token auth: 200 route when the email owns a routable grove,
     * 404 unknown grove, 403 otherwise (unknown email, non-owner, or not ready).
     */
    @PostMapping("/authorize-owner")
    public ResponseEntity<GatewayGroveResponse> authorizeOwner(@Valid @RequestBody AuthorizeOwnerRequest request) {
        var route = gatewayGroveService.authorizeOwner(request.groveId(), request.email());
        if (route.isPresent()) {
            return ResponseEntity.ok(route.get());
        }
        return gatewayGroveService.exists(request.groveId())
            ? ResponseEntity.status(HttpStatus.FORBIDDEN).build()
            : ResponseEntity.notFound().build();
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :trellis:test --tests "dev.orchard.api.controller.GatewayGroveControllerTest"`
Expected: PASS (all 7 tests)

- [ ] **Step 5: Commit**

```bash
git add trellis/src/main/java/dev/orchard/api/controller/GatewayGroveController.java trellis/src/test/java/dev/orchard/api/controller/GatewayGroveControllerTest.java
git commit -m "feat(trellis): add /api/gateway endpoints for SSH gateway routing"
```

---

### Task 5: Guard `CultivatorAuthFilter` against service tokens

`CultivatorAuthFilter` (a global `@Component` running on every validated JWT) calls `CultivatorService.findOrCreateCultivator` with sub/email/name/picture. A `client_credentials` service token has sub = client id and **no email** — today it would resolve into a garbage cultivator. Skip cultivator resolution when the JWT lacks an email claim.

**Files:**
- Modify: `trellis/src/main/java/dev/orchard/trellis/security/CultivatorAuthFilter.java:49-60`
- Test: `trellis/src/test/java/dev/orchard/trellis/security/CultivatorAuthFilterTest.java` (new)

- [ ] **Step 1: Write the failing tests**

Create `trellis/src/test/java/dev/orchard/trellis/security/CultivatorAuthFilterTest.java`:

```java
package dev.orchard.trellis.security;

import dev.orchard.api.service.CultivatorService;
import dev.orchard.core.model.Cultivator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CultivatorAuthFilterTest {

    private static Jwt jwtWith(String subject, String email) {
        return jwtWith(subject, email, null, null);
    }

    private static Jwt jwtWith(String subject, String email, String picture, String name) {
        var builder = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject(subject);
        if (email != null) {
            builder = builder.claim("email", email);
        }
        if (picture != null) {
            builder = builder.claim("picture", picture);
        }
        if (name != null) {
            builder = builder.claim("name", name);
        }
        return builder.build();
    }

    @Test
    void skipsCultivatorResolutionWhenJwtHasNoEmailClaim() throws Exception {
        CultivatorService service = mock(CultivatorService.class);
        CultivatorAuthFilter filter = new CultivatorAuthFilter(service);
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwtWith("orchard-gateway", null)));

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verifyNoInteractions(service);
        assertThat(request.getAttribute("cultivatorId")).isNull();
        assertThat(chain.getRequest()).isSameAs(request); // chain continued
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesCultivatorWhenJwtHasEmailClaim() throws Exception {
        CultivatorService service = mock(CultivatorService.class);
        CultivatorAuthFilter filter = new CultivatorAuthFilter(service);
        UUID cultivatorId = UUID.randomUUID();
        when(service.findOrCreateCultivator(
                "google", "goog-123", "alice@example.com", "alice@example.com", "https://pic", "Alice"))
            .thenReturn(new Cultivator(cultivatorId, "alice@example.com", "alice@example.com",
                "google", "goog-123", "https://pic", "Alice", Instant.now(), Instant.now()));
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwtWith("goog-123", "alice@example.com", "https://pic", "Alice")));

        MockHttpServletRequest request = new MockHttpServletRequest();
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(service).findOrCreateCultivator(
            "google", "goog-123", "alice@example.com", "alice@example.com", "https://pic", "Alice");
        assertThat(request.getAttribute("cultivatorId")).isEqualTo(cultivatorId);
        SecurityContextHolder.clearContext();
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :trellis:test --tests "dev.orchard.trellis.security.CultivatorAuthFilterTest"`
Expected: `skipsCultivatorResolutionWhenJwtHasNoEmailClaim` FAILS (service is still invoked / cultivatorId set)

- [ ] **Step 3: Add the email-claim guard**

In `trellis/src/main/java/dev/orchard/trellis/security/CultivatorAuthFilter.java`, replace the body of the `if (authentication instanceof JwtAuthenticationToken jwtAuth)` block. Current code (lines 50-60):

```java
            Jwt jwt = jwtAuth.getToken();

            // fence is the sole JWT issuer regardless of upstream IdP, so the issuer URL no
            // longer identifies the upstream provider. Hardcoded until multi-IdP support
            // (orchard#196) adds a provider claim to the token itself.
            String provider = "google";
            String providerId = jwt.getSubject();
            String email = jwt.getClaimAsString("email");
            String username = resolveUsername(jwt);
            String avatarUrl = jwt.getClaimAsString("picture");
            String displayName = jwt.getClaimAsString("name");
```

Replace with (note: the duplicate `String email` declaration is removed — declaring it twice would not compile):

```java
            Jwt jwt = jwtAuth.getToken();

            // Service tokens (e.g. the SSH gateway's client_credentials JWT) carry no
            // email claim and must not be resolved into a cultivator. Interactive user
            // tokens always carry email via the openid scope.
            String email = jwt.getClaimAsString("email");
            if (email == null || email.isBlank()) {
                log.debug("JWT has no email claim; skipping cultivator resolution (service token?)");
                filterChain.doFilter(request, response);
                return;
            }

            // fence is the sole JWT issuer regardless of upstream IdP, so the issuer URL no
            // longer identifies the upstream provider. Hardcoded until multi-IdP support
            // (orchard#196) adds a provider claim to the token itself.
            String provider = "google";
            String providerId = jwt.getSubject();
            String username = resolveUsername(jwt);
            String avatarUrl = jwt.getClaimAsString("picture");
            String displayName = jwt.getClaimAsString("name");
```

The `if (providerId != null)` block, `resolveUsername`, and the trailing `filterChain.doFilter(request, response);` all stay unchanged.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :trellis:test --tests "dev.orchard.trellis.security.*"`
Expected: PASS — both new tests + existing `DevCultivatorAuthFilterTest`

- [ ] **Step 5: Commit**

```bash
git add trellis/src/main/java/dev/orchard/trellis/security/CultivatorAuthFilter.java trellis/src/test/java/dev/orchard/trellis/security/CultivatorAuthFilterTest.java
git commit -m "fix(trellis): skip cultivator resolution for JWTs without email claim"
```

---

### Task 6: Full build + verify

- [ ] **Step 1: Run the full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — all modules green (core, roots, harvest, nursery, greenhouse, apiary, trellis, trowel, fence, integration-tests)

- [ ] **Step 2: Confirm gateway endpoints are reachable through the secured chain (manual smoke)**

The endpoints live under `/api/**` so they are already authenticated by `securedFilterChain` when `orchard.security.oauth2.enabled=true`; no `SecurityConfig` change is needed. With the service running in default (permissive) dev mode, verify:

```bash
curl -s http://localhost:8080/api/gateway/groves/00000000-0000-0000-0000-000000000000 -o /dev/null -w "%{http_code}\n"
```
Expected: `404` (permissive dev chain; unknown grove).

- [ ] **Step 3: Commit any test-support files staged along the way**

```bash
git status
```
Expected: working tree clean (all changes committed in Tasks 1-5).

---

## Out of Scope (this phase)

- The gateway module (MINA SSHD, authenticators, relay) — Phase 3.
- Fence `/gateway-token` + confidential client — Phase 2.
- Trowel `ssh-key` CLI, connect rewire, dev-server — Phase 4.
- Any change to `Grove.getSshConnectionString()` / `GroveResponse.sshConnectionString` (external contract; see spec Out-of-Scope note).
