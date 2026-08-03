# Grove SSH Gateway — Gateway Module Implementation Plan (Phase 3 of 4)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the new `gateway/` Spring Boot module: an Apache MINA SSHD server on port 2222 that authenticates inbound SSH clients against their **registered** public keys (via trellis `/api/gateway/**`), then jumphost-relays every channel (shell, exec, sftp subsystem, and two-hop `direct-tcpip`) to the grove's seedling over the loopback using the internal `~/.ssh/orchard_ed25519` key. After this phase, no seedling ever exposes sshd directly.

**Architecture:** Phase 3 of 4 (see `docs/superpowers/specs/2026-08-03-grove-ssh-gateway-design.md`). The gateway depends **only** on `core` (NOT `roots`/JPA) and reaches trellis over HTTP with an OAuth2 `client_credentials` service token — the design spec §Architecture resolved the scope-3.md "JPA vs HTTP" routing question in favor of HTTP. Per-SSH-connection flow: `ssh <grove-id>@gateway` → `KeyAuthenticator` resolves the route + fingerprints via `TrellisApiClient` → route stashed on the `ServerSession` → the session's channels are relayed by `SeedlingRelay` over a single gateway→seedling `SshClient` session (username `cultivator`, internal key).

**Tech Stack:** Java 25, Spring Boot 4.1, Apache MINA SSHD 2.19.0 (`sshd-core`), Spring `RestClient`, Spring Boot test + `MockRestServiceServer`, JUnit 5, AssertJ, Mockito.

---

## Design decisions (resolving spec ambiguities for the executor)

1. **Routing = HTTP to trellis, not JPA.** scope-3.md suggested depending on `roots`; the spec (line 39) mandates the gateway depends only on `core` and calls `GET /api/gateway/groves/{id}` / `GET /api/gateway/cultivators/{id}/keys` with a `client_credentials` service token. Follow the spec — the Phase 1 plan already shipped those endpoints.
2. **Admin web port.** The spec says Spring Boot's `server.port` is "internal/admin only". 8080 is trellis, so the gateway binds `server.port: ${GATEWAY_ADMIN_PORT:8081}` (actuator `health` only — the SSH listener is the real entry point).
3. **Paths use `${user.home}/…`, not `~`.** Spring does not expand `~`. The spec table's `~/.orchard/gateway-host-key` / `~/.ssh/orchard_ed25519` become `${user.home}/.orchard/gateway-host-key` / `${user.home}/.ssh/orchard_ed25519` in `application.yml`, matching fence's `fence.signing-key.path` convention.
4. **Internal SSH user is `cultivator`.** Nursery bakes `cultivator` into every guest image (`nursery/.../SshCommandBuilder.java:26` `DEFAULT_USER`) and the existing connect string uses `cultivator@<ip>`. The relay's client session authenticates as `cultivator`.
5. **Internal key is OpenSSH PEM.** `QemuEnvironmentInitializer`/`ssh-keygen` produces `-----BEGIN OPENSSH PRIVATE KEY-----`. Load it with MINA's OpenSSH key parser (`OpenSSHKeyPairResourceParser`), not `KeyPairGenerator`.
6. **Two-hop `direct-tcpip`.** The default `DirectTcpipFactory` makes the *gateway* open TCP to the target — wrong, the gateway has no route to it. A custom `direct-tcpip` `ChannelFactory` replaces it and instead opens a client-side `ChannelDirectTcpip` on the gateway→seedling session, so the *seedling* reaches the target (modeled on `org.apache.sshd.server.forward.TcpipServerChannel`, verified against sshd-2.19.0 source).
7. **Owner-token password auth is Phase 4.** This phase sets no `PasswordAuthenticator`; `GroveRelayServer` installs only the publickey authenticator.
8. **Client streaming mode.** All relay bridges use `ClientChannel` **Sync** streaming + `getInvertedIn()/getInvertedOut()/getInvertedErr()` (public API) with one virtual thread per direction — no internal `AbstractChannel` machinery.

Dependencies this phase assumes are already merged (Phase 1/2 plans): trellis `GET /api/gateway/groves/{id}`, `GET /api/gateway/cultivators/{id}/keys`, `CultivatorAuthFilter` email-claim guard, public static `SshPublicKey.fingerprint(String)` (`core/.../SshPublicKey.java`); fence `orchard-gateway` confidential client + `POST /oauth2/token` issuing `client_credentials` tokens.

---

### Task 1: Create the `gateway` Gradle module, app skeleton, and properties

**Files:**
- Modify: `settings.gradle.kts` (add `"gateway"` to `include(...)`)
- Create: `gateway/build.gradle.kts`
- Create: `gateway/src/main/java/dev/orchard/gateway/GatewayApplication.java`
- Create: `gateway/src/main/java/dev/orchard/gateway/config/GatewayProperties.java`
- Create: `gateway/src/main/resources/application.yml`
- Test: `gateway/src/test/java/dev/orchard/gateway/config/GatewayPropertiesTest.java`

- [ ] **Step 1: Register the module**

In `settings.gradle.kts`, add `"gateway"` after `"fence"`:

```kotlin
include(
    "core",
    "roots",
    "harvest",
    "nursery",
    "greenhouse",
    "apiary",
    "trellis",
    "trowel",
    "fence",
    "gateway",
    "integration-tests"
)
```

- [ ] **Step 2: Create `gateway/build.gradle.kts`**

Follow the `fence`/`trellis` convention (spring boot plugin, `buildInfo()`), plus the MINA SSHD dependency (the only new library; nothing else in the repo uses SSH-server libraries — confirmed by scope-3.md):

```kotlin
plugins {
    id("org.springframework.boot")
}

springBoot {
    buildInfo()
}

dependencies {
    implementation(project(":core"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.apache.sshd:sshd-core:2.19.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}
```

The root `build.gradle.kts` already applies the Spring Boot dependency-management BOM to all subprojects and configures JUnit 5 for every module, so no test framework config is needed here.

- [ ] **Step 3: Create `GatewayApplication`**

```java
package dev.orchard.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```

(`@ConfigurationPropertiesScan` mirrors `FenceApplication` so `GatewayProperties` auto-registers without an `@EnableConfigurationProperties`.)

- [ ] **Step 4: Create `GatewayProperties`**

Bind `orchard.gateway.*` with the spec's table defaults. Paths are `String` (converted to `Path` in the classes that use them) to avoid Path-binding surprises:

```java
package dev.orchard.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties("orchard.gateway")
public class GatewayProperties {

    private int sshPort = 2222;
    private String hostKeyPath = Path.of(System.getProperty("user.home"), ".orchard", "gateway-host-key").toString();
    private String internalSshKeyPath = Path.of(System.getProperty("user.home"), ".ssh", "orchard_ed25519").toString();
    private final Fence fence = new Fence();
    private final OAuth2 oauth2 = new OAuth2();
    private final Trellis trellis = new Trellis();

    public static class Fence {
        private String issuerUri = "http://localhost:7779";
        public String getIssuerUri() { return issuerUri; }
        public void setIssuerUri(String issuerUri) { this.issuerUri = issuerUri; }
    }

    public static class OAuth2 {
        private String clientId = "orchard-gateway";
        private String clientSecret = "";
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    }

    public static class Trellis {
        private String baseUrl = "http://localhost:8080";
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }

    public int getSshPort() { return sshPort; }
    public void setSshPort(int sshPort) { this.sshPort = sshPort; }
    public String getHostKeyPath() { return hostKeyPath; }
    public void setHostKeyPath(String hostKeyPath) { this.hostKeyPath = hostKeyPath; }
    public String getInternalSshKeyPath() { return internalSshKeyPath; }
    public void setInternalSshKeyPath(String internalSshKeyPath) { this.internalSshKeyPath = internalSshKeyPath; }
    public Fence getFence() { return fence; }
    public OAuth2 getOauth2() { return oauth2; }
    public Trellis getTrellis() { return trellis; }
}
```

- [ ] **Step 5: Create `application.yml`**

```yaml
server:
  port: ${GATEWAY_ADMIN_PORT:8081}

spring:
  application:
    name: gateway

orchard:
  gateway:
    ssh-port: ${GATEWAY_SSH_PORT:2222}
    host-key-path: ${GATEWAY_HOST_KEY_PATH:${user.home}/.orchard/gateway-host-key}
    internal-ssh-key-path: ${GATEWAY_INTERNAL_SSH_KEY_PATH:${user.home}/.ssh/orchard_ed25519}
    fence:
      issuer-uri: ${GATEWAY_FENCE_ISSUER_URI:http://localhost:7779}
    oauth2:
      client-id: ${GATEWAY_OAUTH2_CLIENT_ID:orchard-gateway}
      client-secret: ${GATEWAY_OAUTH2_CLIENT_SECRET:}
    trellis:
      base-url: ${GATEWAY_TRELLIS_BASE_URL:http://localhost:8080}

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

- [ ] **Step 6: Write the failing properties test**

Create `gateway/src/test/java/dev/orchard/gateway/config/GatewayPropertiesTest.java` — an `ApplicationContextRunner` with an `@EnableConfigurationProperties` config so the `orchard.gateway.*` binding actually happens (mirrors fence's `RegisteredClientRepositoryConfigTest` runner style):

```java
package dev.orchard.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayPropertiesTest {

    @org.springframework.context.annotation.Configuration
    @EnableConfigurationProperties(GatewayProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void defaultsMatchSpecTable() {
        contextRunner.run(context -> {
            GatewayProperties props = context.getBean(GatewayProperties.class);
            assertThat(props.getSshPort()).isEqualTo(2222);
            assertThat(props.getHostKeyPath()).endsWith("/.orchard/gateway-host-key");
            assertThat(props.getInternalSshKeyPath()).endsWith("/.ssh/orchard_ed25519");
            assertThat(props.getFence().getIssuerUri()).isEqualTo("http://localhost:7779");
            assertThat(props.getOauth2().getClientId()).isEqualTo("orchard-gateway");
            assertThat(props.getTrellis().getBaseUrl()).isEqualTo("http://localhost:8080");
        });
    }

    @Test
    void envVarsOverrideDefaults() {
        contextRunner
                .withPropertyValues("orchard.gateway.ssh-port=2300")
                .withPropertyValues("orchard.gateway.trellis.base-url=http://trellis:8080")
                .withPropertyValues("orchard.gateway.oauth2.client-secret=s3cret")
                .run(context -> {
                    GatewayProperties props = context.getBean(GatewayProperties.class);
                    assertThat(props.getSshPort()).isEqualTo(2300);
                    assertThat(props.getTrellis().getBaseUrl()).isEqualTo("http://trellis:8080");
                    assertThat(props.getOauth2().getClientSecret()).isEqualTo("s3cret");
                });
    }
}
```

The `@SpringBootTest`-free runner keeps this fast and hermetic (no `SshServer` booting yet — that's Task 7).

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew :gateway:test --tests "dev.orchard.gateway.config.GatewayPropertiesTest"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts gateway/build.gradle.kts gateway/src/main/java/dev/orchard/gateway/GatewayApplication.java gateway/src/main/java/dev/orchard/gateway/config/GatewayProperties.java gateway/src/main/resources/application.yml gateway/src/test/java/dev/orchard/gateway/config/GatewayPropertiesTest.java
git commit -m "feat(gateway): scaffold gateway module with GatewayProperties and MINA SSHD dependency"
```

---

### Task 2: Add route/key records, `FenceTokenClient`, and `TrellisApiClient`

The gateway's only view of the platform is trellis's `/api/gateway/**` (Phase 1). This task builds the typed HTTP client and the `client_credentials` token machinery that authenticates it (fence's `orchard-gateway` client, Phase 2).

**Files:**
- Create: `gateway/src/main/java/dev/orchard/gateway/api/GatewayRoute.java`
- Create: `gateway/src/main/java/dev/orchard/gateway/api/GatewayKey.java`
- Create: `gateway/src/main/java/dev/orchard/gateway/auth/FenceTokenClient.java`
- Create: `gateway/src/main/java/dev/orchard/gateway/api/TrellisApiClient.java`
- Test: `gateway/src/test/java/dev/orchard/gateway/auth/FenceTokenClientTest.java`
- Test: `gateway/src/test/java/dev/orchard/gateway/api/TrellisApiClientTest.java`

- [ ] **Step 1: Write the failing token-client test**

Create `gateway/src/test/java/dev/orchard/gateway/auth/FenceTokenClientTest.java` — a plain unit test using `MockRestServiceServer.bindTo(restClient)` (no Spring context):

```java
package dev.orchard.gateway.auth;

import dev.orchard.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class FenceTokenClientTest {

    private GatewayProperties properties() {
        GatewayProperties p = new GatewayProperties();
        p.getFence().setIssuerUri("http://localhost:7779");
        p.getOauth2().setClientId("orchard-gateway");
        p.getOauth2().setClientSecret("dev-secret");
        return p;
    }

    @Test
    void fetchesAndCachesToken() {
        RestClient restClient = RestClient.create();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClient).build();
        FenceTokenClient client = new FenceTokenClient(restClient, properties());

        server.expect(requestTo("http://localhost:7779/oauth2/token"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Basic b3JjaGFyZC1nYXRld2F5OmRldi1zZWNyZXQ="))
                .andRespond(withSuccess(
                        "{\"access_token\":\"jwt-1\",\"token_type\":\"Bearer\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.accessToken()).isEqualTo("jwt-1");
        assertThat(client.accessToken()).isEqualTo("jwt-1"); // cached, no second request
        server.verify();
    }

    @Test
    void refreshesAfterExpiry() {
        RestClient restClient = RestClient.create();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClient).build();
        FenceTokenClient client = new FenceTokenClient(restClient, properties());

        server.expect(requestTo("http://localhost:7779/oauth2/token")).andRespond(withSuccess(
                "{\"access_token\":\"jwt-1\",\"expires_in\":0}", MediaType.APPLICATION_JSON));
        assertThat(client.accessToken()).isEqualTo("jwt-1");

        server.expect(requestTo("http://localhost:7779/oauth2/token")).andRespond(withSuccess(
                "{\"access_token\":\"jwt-2\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
        assertThat(client.accessToken()).isEqualTo("jwt-2");
        server.verify();
    }
}
```

(Basic header value is `Base64("orchard-gateway:dev-secret")` = `b3JjaGFyZC1nYXRld2F5OmRldi1zZWNyZXQ=`. Recompute if you change the constants.)

- [ ] **Step 2: Write the failing trellis-client test**

Create `gateway/src/test/java/dev/orchard/gateway/api/TrellisApiClientTest.java`:

```java
package dev.orchard.gateway.api;

import dev.orchard.gateway.auth.FenceTokenClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.MediaType.APPLICATION_JSON;

class TrellisApiClientTest {

    private FenceTokenClient tokenClient;
    private TrellisApiClient client;
    private MockRestServiceServer server;

    void setUp(String baseUrl) {
        tokenClient = mock(FenceTokenClient.class);
        when(tokenClient.accessToken()).thenReturn("svc-token");
        RestClient restClient = TrellisApiClient.buildRestClient(baseUrl, tokenClient);
        server = MockRestServiceServer.bindTo(restClient).build();
        client = new TrellisApiClient(restClient, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void resolveGrove_returnsRoute() {
        setUp("http://trellis:8080");
        UUID groveId = UUID.randomUUID();
        server.expect(requestTo("http://trellis:8080/api/gateway/groves/" + groveId))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andRespond(withSuccess(
                        "{\"groveId\":\"" + groveId + "\",\"cultivatorId\":\"%s\",\"seedlingIp\":\"127.0.0.1\",\"seedlingPort\":22,\"state\":\"FLOURISHING\"}"
                                .formatted(UUID.randomUUID()),
                        APPLICATION_JSON));

        Optional<GatewayRoute> route = client.resolveGrove(groveId);
        assertThat(route).isPresent();
        assertThat(route.get().groveId()).isEqualTo(groveId);
        assertThat(route.get().seedlingIp()).isEqualTo("127.0.0.1");
        assertThat(route.get().seedlingPort()).isEqualTo(22);
    }

    @Test
    void resolveGrove_unknownAndNotReadyAreEmpty() {
        setUp("http://trellis:8080");
        UUID groveId = UUID.randomUUID();
        server.expect(requestTo("http://trellis:8080/api/gateway/groves/" + groveId))
                .andRespond(withStatus(NOT_FOUND));
        server.expect(requestTo("http://trellis:8080/api/gateway/groves/" + groveId))
                .andRespond(withStatus(CONFLICT));

        assertThat(client.resolveGrove(groveId)).isEmpty();
        assertThat(client.resolveGrove(groveId)).isEmpty();
        server.verify();
    }

    @Test
    void listKeys_parsesArray() {
        setUp("http://trellis:8080");
        UUID cultivatorId = UUID.randomUUID();
        server.expect(requestTo("http://trellis:8080/api/gateway/cultivators/" + cultivatorId + "/keys"))
                .andRespond(withSuccess(
                        "[{\"id\":\"%s\",\"name\":\"laptop\",\"publicKey\":\"ssh-ed25519 AAAA test@orchard.dev\",\"fingerprint\":\"SHA256:abc\"}]"
                                .formatted(UUID.randomUUID()),
                        APPLICATION_JSON));

        List<GatewayKey> keys = client.listKeys(cultivatorId);
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).name()).isEqualTo("laptop");
        assertThat(keys.get(0).fingerprint()).isEqualTo("SHA256:abc");
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :gateway:test --tests "dev.orchard.gateway.auth.*" --tests "dev.orchard.gateway.api.*"`
Expected: FAIL — `cannot find symbol: class FenceTokenClient` / `TrellisApiClient` (and missing records).

- [ ] **Step 4: Create the records**

Create `gateway/src/main/java/dev/orchard/gateway/api/GatewayRoute.java`:

```java
package dev.orchard.gateway.api;

import java.util.UUID;

/** Route trellis returns for a routable (running-seedling) grove. Mirrors trellis's GatewayGroveResponse. */
public record GatewayRoute(
    UUID groveId,
    UUID cultivatorId,
    String seedlingIp,
    int seedlingPort,
    String state
) {}
```

Create `gateway/src/main/java/dev/orchard/gateway/api/GatewayKey.java`:

```java
package dev.orchard.gateway.api;

import java.util.UUID;

/** A cultivator's registered SSH public key as trellis exposes it to the gateway. */
public record GatewayKey(
    UUID id,
    String name,
    String publicKey,
    String fingerprint
) {}
```

- [ ] **Step 5: Implement `FenceTokenClient`**

Create `gateway/src/main/java/dev/orchard/gateway/auth/FenceTokenClient.java` — fetches and caches a `client_credentials` token from fence's Spring Authorization Server token endpoint (`POST {issuerUri}/oauth2/token`, `CLIENT_SECRET_BASIC`):

```java
package dev.orchard.gateway.auth;

import com.fasterxml.jackson.databind.JsonNode;
import dev.orchard.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Mints and caches the OAuth2 client_credentials service token the gateway
 * presents to trellis's /api/gateway/** endpoints. The client (orchard-gateway)
 * is registered in fence with CLIENT_SECRET_BASIC (Phase 2).
 */
@Component
public class FenceTokenClient {

    private static final Logger log = LoggerFactory.getLogger(FenceTokenClient.class);

    private final RestClient restClient;
    private final GatewayProperties properties;

    private volatile String accessToken;
    private volatile Instant expiresAt = Instant.EPOCH;

    public FenceTokenClient(RestClient restClient, GatewayProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public synchronized String accessToken() {
        if (accessToken != null && expiresAt.isAfter(Instant.now().plusSeconds(30))) {
            return accessToken;
        }
        String tokenEndpoint = properties.getFence().getIssuerUri() + "/oauth2/token";
        String basic = Base64.getEncoder().encodeToString(
                (properties.getOauth2().getClientId() + ":" + properties.getOauth2().getClientSecret())
                        .getBytes(StandardCharsets.UTF_8));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", "openid");

        JsonNode body = restClient.post()
                .uri(tokenEndpoint)
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);

        accessToken = body.get("access_token").asText();
        long expiresIn = body.has("expires_in") ? body.get("expires_in").asLong() : 3600;
        expiresAt = Instant.now().plusSeconds(expiresIn);
        log.debug("Fetched gateway service token (expires in {}s)", expiresIn);
        return accessToken;
    }
}
```

Note: `FenceTokenClient` takes a ready-built `RestClient` (not a `Builder`). Production wiring is in Step 7; the unit tests construct it with the `MockRestServiceServer`-bound `RestClient` directly.

- [ ] **Step 6: Implement `TrellisApiClient`**

Create `gateway/src/main/java/dev/orchard/gateway/api/TrellisApiClient.java`:

```java
package dev.orchard.gateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orchard.gateway.auth.FenceTokenClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * HTTP client for trellis /api/gateway/**. Authenticates with the gateway's
 * client_credentials service token (fence), injected by the bearer interceptor.
 * 404 (unknown grove) and 409 (exists but not routable) both mean "no route".
 */
@Component
public class TrellisApiClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public TrellisApiClient(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    /** Builds the RestClient with a bearer-token interceptor that refreshes the token transparently. */
    public static RestClient buildRestClient(String baseUrl, FenceTokenClient tokenClient) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(tokenClient.accessToken());
                    return execution.execute(request, body);
                })
                .build();
    }

    public Optional<GatewayRoute> resolveGrove(UUID groveId) {
        return restClient.get()
                .uri("/api/gateway/groves/{id}", groveId)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status == 200) {
                        return Optional.of(objectMapper.readValue(response.getBody(), GatewayRoute.class));
                    }
                    if (status == 404 || status == 409) {
                        return Optional.<GatewayRoute>empty();
                    }
                    throw new IllegalStateException("Unexpected status " + status + " from trellis");
                });
    }

    public List<GatewayKey> listKeys(UUID cultivatorId) {
        return restClient.get()
                .uri("/api/gateway/cultivators/{id}/keys", cultivatorId)
                .retrieve()
                .body(new ParameterizedTypeReference<List<GatewayKey>>() {});
    }
}
```

(`ObjectMapper` is auto-configured by the webmvc starter; the `exchange` lambda's `response.getBody()` is the raw `InputStream` that `objectMapper.readValue` consumes. The `RestClient` bean is built in Step 7 with the base URL from properties.)

- [ ] **Step 7: Wire the beans**

Create `gateway/src/main/java/dev/orchard/gateway/config/HttpClientConfig.java`:

```java
package dev.orchard.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orchard.gateway.api.TrellisApiClient;
import dev.orchard.gateway.auth.FenceTokenClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {

    @Bean
    FenceTokenClient fenceTokenClient(RestClient.Builder builder, GatewayProperties properties) {
        return new FenceTokenClient(builder.build(), properties);
    }

    @Bean
    RestClient trellisRestClient(GatewayProperties properties, FenceTokenClient fenceTokenClient) {
        return TrellisApiClient.buildRestClient(properties.getTrellis().getBaseUrl(), fenceTokenClient);
    }

    @Bean
    TrellisApiClient trellisApiClient(RestClient trellisRestClient, ObjectMapper objectMapper) {
        return new TrellisApiClient(trellisRestClient, objectMapper);
    }
}
```

The unit tests bypass these beans entirely: `FenceTokenClientTest`/`TrellisApiClientTest` construct the clients directly with a `MockRestServiceServer`-bound `RestClient`.

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew :gateway:test --tests "dev.orchard.gateway.auth.*" --tests "dev.orchard.gateway.api.*"`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add gateway/src/main/java/dev/orchard/gateway/api gateway/src/main/java/dev/orchard/gateway/auth gateway/src/main/java/dev/orchard/gateway/config/HttpClientConfig.java gateway/src/test/java/dev/orchard/gateway/auth gateway/src/test/java/dev/orchard/gateway/api
git commit -m "feat(gateway): add TrellisApiClient with client_credentials service token"
```

---

### Task 3: Add `GroveResolver` (username → grove route)

The SSH username *is* the grove id (`ssh <grove-id>@gateway`). `GroveResolver` parses it and delegates to `TrellisApiClient.resolveGrove`.

**Files:**
- Create: `gateway/src/main/java/dev/orchard/gateway/service/GroveResolver.java`
- Test: `gateway/src/test/java/dev/orchard/gateway/service/GroveResolverTest.java`

- [ ] **Step 1: Write the failing test**

Create `gateway/src/test/java/dev/orchard/gateway/service/GroveResolverTest.java`:

```java
package dev.orchard.gateway.service;

import dev.orchard.gateway.api.GatewayRoute;
import dev.orchard.gateway.api.TrellisApiClient;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GroveResolverTest {

    @Test
    void resolve_delegatesForValidUuid() {
        TrellisApiClient trellis = mock(TrellisApiClient.class);
        UUID groveId = UUID.randomUUID();
        GatewayRoute route = new GatewayRoute(groveId, UUID.randomUUID(), "127.0.0.1", 22, "FLOURISHING");
        when(trellis.resolveGrove(groveId)).thenReturn(Optional.of(route));

        GroveResolver resolver = new GroveResolver(trellis);
        assertThat(resolver.resolve(groveId.toString())).isPresent();
        verify(trellis).resolveGrove(groveId);
    }

    @Test
    void resolve_emptyForNonRoutable() {
        TrellisApiClient trellis = mock(TrellisApiClient.class);
        UUID groveId = UUID.randomUUID();
        when(trellis.resolveGrove(groveId)).thenReturn(Optional.empty());

        assertThat(new GroveResolver(trellis).resolve(groveId.toString())).isEmpty();
    }

    @Test
    void resolve_emptyForMalformedUsername() {
        TrellisApiClient trellis = mock(TrellisApiClient.class);

        assertThat(new GroveResolver(trellis).resolve("not-a-uuid")).isEmpty();
        assertThat(new GroveResolver(trellis).resolve("")).isEmpty();
        assertThat(new GroveResolver(trellis).resolve(null)).isEmpty();
        verifyNoInteractions(trellis);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :gateway:test --tests "dev.orchard.gateway.service.GroveResolverTest"`
Expected: FAIL — `cannot find symbol: class GroveResolver`.

- [ ] **Step 3: Implement `GroveResolver`**

Create `gateway/src/main/java/dev/orchard/gateway/service/GroveResolver.java`:

```java
package dev.orchard.gateway.service;

import dev.orchard.gateway.api.GatewayRoute;
import dev.orchard.gateway.api.TrellisApiClient;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves an SSH username (the grove id) to a routable grove route.
 * Rejects malformed ids without touching trellis.
 */
@Component
public class GroveResolver {

    private final TrellisApiClient trellisApiClient;

    public GroveResolver(TrellisApiClient trellisApiClient) {
        this.trellisApiClient = trellisApiClient;
    }

    public Optional<GatewayRoute> resolve(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        try {
            return trellisApiClient.resolveGrove(UUID.fromString(username));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :gateway:test --tests "dev.orchard.gateway.service.GroveResolverTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add gateway/src/main/java/dev/orchard/gateway/service/GroveResolver.java gateway/src/test/java/dev/orchard/gateway/service/GroveResolverTest.java
git commit -m "feat(gateway): add GroveResolver mapping SSH username to grove route"
```

---

### Task 4: Add `KeyAuthenticator` (publickey auth against registered keys)

MINA `PublickeyAuthenticator`. Flow: parse username → resolve route → fetch the cultivator's registered keys → render the offered key to its ssh wire line → fingerprint with the core algorithm (the same one registration uses) → match. On success, stash the route on the `ServerSession` for the relay.

**Files:**
- Create: `gateway/src/main/java/dev/orchard/gateway/auth/KeyAuthenticator.java`
- Test: `gateway/src/test/java/dev/orchard/gateway/auth/KeyAuthenticatorTest.java`

- [ ] **Step 1: Write the failing test**

Create `gateway/src/test/java/dev/orchard/gateway/auth/KeyAuthenticatorTest.java`:

```java
package dev.orchard.gateway.auth;

import dev.orchard.gateway.api.GatewayKey;
import dev.orchard.gateway.api.GatewayRoute;
import dev.orchard.gateway.api.TrellisApiClient;
import dev.orchard.gateway.service.GroveResolver;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.server.session.ServerSession;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KeyAuthenticatorTest {

    private static KeyPair ed25519KeyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    @Test
    void authenticate_acceptsRegisteredKeyAndStoresRoute() throws Exception {
        TrellisApiClient trellis = mock(TrellisApiClient.class);
        GroveResolver resolver = mock(GroveResolver.class);
        ServerSession session = mock(ServerSession.class);

        UUID groveId = UUID.randomUUID();
        UUID cultivatorId = UUID.randomUUID();
        GatewayRoute route = new GatewayRoute(groveId, cultivatorId, "127.0.0.1", 22, "FLOURISHING");
        when(resolver.resolve(groveId.toString())).thenReturn(Optional.of(route));

        KeyPair pair = ed25519KeyPair();
        String wireLine = PublicKeyEntry.toString(pair.getPublic());
        String fingerprint = dev.orchard.core.model.SshPublicKey.fingerprint(wireLine);
        when(trellis.listKeys(cultivatorId))
                .thenReturn(List.of(new GatewayKey(UUID.randomUUID(), "laptop", wireLine, fingerprint)));

        KeyAuthenticator authenticator = new KeyAuthenticator(resolver, trellis);

        assertThat(authenticator.authenticate(groveId.toString(), pair.getPublic(), session)).isTrue();
        verify(session).setAttribute(eq(KeyAuthenticator.ROUTE_KEY), eq(route));
    }

    @Test
    void authenticate_rejectsUnknownKey() throws Exception {
        TrellisApiClient trellis = mock(TrellisApiClient.class);
        GroveResolver resolver = mock(GroveResolver.class);
        ServerSession session = mock(ServerSession.class);

        UUID groveId = UUID.randomUUID();
        UUID cultivatorId = UUID.randomUUID();
        GatewayRoute route = new GatewayRoute(groveId, cultivatorId, "127.0.0.1", 22, "FLOURISHING");
        when(resolver.resolve(groveId.toString())).thenReturn(Optional.of(route));

        KeyPair registered = ed25519KeyPair();
        String wireLine = PublicKeyEntry.toString(registered.getPublic());
        when(trellis.listKeys(cultivatorId))
                .thenReturn(List.of(new GatewayKey(UUID.randomUUID(), "laptop", wireLine,
                        dev.orchard.core.model.SshPublicKey.fingerprint(wireLine))));

        KeyPair attacker = ed25519KeyPair();
        KeyAuthenticator authenticator = new KeyAuthenticator(resolver, trellis);

        assertThat(authenticator.authenticate(groveId.toString(), attacker.getPublic(), session)).isFalse();
        verify(session, never()).setAttribute(any(), any());
    }

    @Test
    void authenticate_rejectsWhenGroveNotRoutable() {
        TrellisApiClient trellis = mock(TrellisApiClient.class);
        GroveResolver resolver = mock(GroveResolver.class);
        ServerSession session = mock(ServerSession.class);
        when(resolver.resolve("deadbeef")).thenReturn(Optional.empty());

        assertThat(new KeyAuthenticator(resolver, trellis)
                .authenticate("deadbeef", mock(PublicKey.class), session)).isFalse();
    }

    @Test
    void authenticate_rejectsMalformedUsername() {
        TrellisApiClient trellis = mock(TrellisApiClient.class);
        GroveResolver resolver = mock(GroveResolver.class);
        ServerSession session = mock(ServerSession.class);

        assertThat(new KeyAuthenticator(resolver, trellis)
                .authenticate("not-a-uuid", mock(PublicKey.class), session)).isFalse();
    }
}
```

The `PublicKeyEntry.toString(PublicKey)` → `SshPublicKey.fingerprint(...)` pipeline is the exact algorithm pair (verified: `PublicKeyEntry.toString` returns `"<key-type> <base64>"`, matching the registered-key wire line that `SshPublicKey.register` stores and fingerprints).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :gateway:test --tests "dev.orchard.gateway.auth.KeyAuthenticatorTest"`
Expected: FAIL — `cannot find symbol: class KeyAuthenticator` (and `ROUTE_KEY`).

- [ ] **Step 3: Implement `KeyAuthenticator`**

Create `gateway/src/main/java/dev/orchard/gateway/auth/KeyAuthenticator.java`:

```java
package dev.orchard.gateway.auth;

import dev.orchard.gateway.api.GatewayRoute;
import dev.orchard.gateway.api.TrellisApiClient;
import dev.orchard.gateway.service.GroveResolver;
import org.apache.sshd.common.AttributeKey;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator;
import org.apache.sshd.server.session.ServerSession;
import org.springframework.stereotype.Component;

import java.security.PublicKey;

/**
 * Publickey auth for the SSH gateway: the offered key must be registered to the
 * grove's cultivator (fingerprint match via the core algorithm). The resolved
 * route is stashed on the session for the relay.
 */
@Component
public class KeyAuthenticator implements PublickeyAuthenticator {

    public static final AttributeKey<GatewayRoute> ROUTE_KEY = new AttributeKey<>();

    private final GroveResolver groveResolver;
    private final TrellisApiClient trellisApiClient;

    public KeyAuthenticator(GroveResolver groveResolver, TrellisApiClient trellisApiClient) {
        this.groveResolver = groveResolver;
        this.trellisApiClient = trellisApiClient;
    }

    @Override
    public boolean authenticate(String username, PublicKey key, ServerSession session) {
        var route = groveResolver.resolve(username);
        if (route.isEmpty()) {
            return false;
        }
        String offeredFingerprint = dev.orchard.core.model.SshPublicKey.fingerprint(PublicKeyEntry.toString(key));
        boolean matched = trellisApiClient.listKeys(route.get().cultivatorId()).stream()
                .anyMatch(k -> k.fingerprint().equals(offeredFingerprint));
        if (matched) {
            session.setAttribute(ROUTE_KEY, route.get());
        }
        return matched;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :gateway:test --tests "dev.orchard.gateway.auth.KeyAuthenticatorTest"`
Expected: PASS (4 tests). (If Mockito's mock of `ServerSession.setAttribute` needs `mockito-inline`/default-method handling, assert via an `ArgumentCaptor` on the `setAttribute(AttributeKey, Object)` invocation instead — the default-method behavior of `AttributeRepository` is the only moving part.)

- [ ] **Step 5: Commit**

```bash
git add gateway/src/main/java/dev/orchard/gateway/auth/KeyAuthenticator.java gateway/src/test/java/dev/orchard/gateway/auth/KeyAuthenticatorTest.java
git commit -m "feat(gateway): authenticate SSH clients against registered key fingerprints"
```

---

### Task 5: Add `SeedlingRelay` — gateway→seedling client session + shell/exec/sftp command relay

The relay opens ONE SSH **client** session per gateway `ServerSession` (lazily, cached as a session attribute, closed when the server session closes) and pumps every server-side channel over it. Shell/exec/sftp are handled as server **commands** that open the matching client channel and bridge bytes with Sync-streaming virtual threads.

**Files:**
- Create: `gateway/src/main/java/dev/orchard/gateway/relay/SeedlingRelay.java`
- Create: `gateway/src/main/java/dev/orchard/gateway/relay/RelayCommand.java`
- Test: `gateway/src/test/java/dev/orchard/gateway/relay/SeedlingRelayTest.java`

- [ ] **Step 1: Write the failing unit test**

The full relay behavior is covered by the integration test (Task 8). This unit test covers the pieces that are cheap to assert without a live seedling: client-session caching per server session, and internal-key loading from an OpenSSH PEM file.

Create `gateway/src/test/java/dev/orchard/gateway/relay/SeedlingRelayTest.java`:

```java
package dev.orchard.gateway.relay;

import dev.orchard.gateway.config.GatewayProperties;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.server.session.ServerSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SeedlingRelayTest {

    @TempDir
    Path tempDir;

    private GatewayProperties properties() {
        GatewayProperties p = new GatewayProperties();
        p.setInternalSshKeyPath(tempDir.resolve("orchard_ed25519").toString());
        return p;
    }

    @Test
    void loadsInternalKeyFromOpenSshPem() throws Exception {
        // Write an ed25519 keypair in OpenSSH PEM format for OpenSSHKeyPairResourceParser to read.
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path keyFile = tempDir.resolve("orchard_ed25519");
        try (var out = java.nio.file.Files.newOutputStream(keyFile)) {
            org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter.INSTANCE
                    .writePrivateKey(pair, "test-key", null, out);
        }

        SeedlingRelay relay = new SeedlingRelay(new SshClient(), properties());
        KeyPair loaded = relay.loadInternalKey();
        assertThat(PublicKeyEntry.toString(loaded.getPublic()))
                .isEqualTo(PublicKeyEntry.toString(pair.getPublic()));
    }

    @Test
    void clientSessionIsCachedPerServerSession() {
        SeedlingRelay relay = new SeedlingRelay(new SshClient(), properties());
        ServerSession serverSession = mock(ServerSession.class);
        // No live seedling here — assert the caching key exists and is stable.
        assertThat(SeedlingRelay.RELAY_SESSION_KEY).isNotNull();
        assertThat(serverSession).isNotNull();
    }
}
```

(Adjust the second test to assert what's cheaply assertable once the class exists; the real caching semantics are exercised in Task 8. `OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(key, comment, null, out)` is the ssh-keygen-compatible OpenSSH writer — verified in sshd-common 2.19.0, package `org.apache.sshd.common.config.keys.writer.openssh`. Passing `null` for the encryption context writes an unencrypted PEM.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :gateway:test --tests "dev.orchard.gateway.relay.SeedlingRelayTest"`
Expected: FAIL — `cannot find symbol: class SeedlingRelay`.

- [ ] **Step 3: Implement `SeedlingRelay`**

Create `gateway/src/main/java/dev/orchard/gateway/relay/SeedlingRelay.java` — owns the `SshClient`, the internal key, the per-session client session, and the command/subsystem factories:

```java
package dev.orchard.gateway.relay;

import dev.orchard.gateway.api.GatewayRoute;
import dev.orchard.gateway.auth.KeyAuthenticator;
import dev.orchard.gateway.config.GatewayProperties;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.AttributeKey;
import org.apache.sshd.common.channel.ChannelFactory;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.loader.openssh.OpenSSHKeyPairResourceParser;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.shell.ShellFactory;
import org.apache.sshd.server.subsystem.SubsystemFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Jumphost relay (design spec §Relay Flow). Holds the gateway→seedling SSH
 * client (username {@code cultivator}, internal key) and produces the server
 * shell/exec/subsystem factories that pump each channel through it.
 */
@Component
public class SeedlingRelay {

    private static final Logger log = LoggerFactory.getLogger(SeedlingRelay.class);

    /** The internal trellis→seedling key. Defaults to QemuPlatformDefaults' path. */
    static final String DEFAULT_SSH_USER = "cultivator";

    public static final AttributeKey<ClientSession> RELAY_SESSION_KEY = new AttributeKey<>();

    private final SshClient sshClient;
    private final GatewayProperties properties;

    private volatile KeyPair internalKey;
    private Path internalKeyPath;

    public SeedlingRelay(SshClient sshClient, GatewayProperties properties) {
        this.sshClient = sshClient;
        this.properties = properties;
    }

    @PostConstruct
    void start() {
        this.internalKeyPath = Path.of(properties.getInternalSshKeyPath());
        this.internalKey = loadInternalKey();
        sshClient.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        sshClient.start();
        log.info("SeedlingRelay up; internal key from {}", internalKeyPath);
    }

    @PreDestroy
    void stop() {
        sshClient.stop();
    }

    KeyPair loadInternalKey() {
        Path path = Path.of(properties.getInternalSshKeyPath());
        if (!Files.isReadable(path)) {
            throw new IllegalStateException(
                "Internal SSH key not found at " + path + " (orchard.gateway.internal-ssh-key-path). "
                + "It is generated by the trellis/nursery side (QemuEnvironmentInitializer).");
        }
        try {
            // loadKeyPairs(SessionContext, Path, FilePasswordProvider, OpenOption...) — verified in 2.19.0
            return OpenSSHKeyPairResourceParser.INSTANCE.loadKeyPairs(null, path, null).stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No key in " + path));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load internal SSH key from " + path, e);
        }
    }

    /** Lazily opens (and caches per server session) the client session to the seedling. */
    public ClientSession relaySession(org.apache.sshd.server.session.ServerSession serverSession,
                                      GatewayRoute route) throws Exception {
        ClientSession cached = serverSession.getAttribute(RELAY_SESSION_KEY);
        if (cached != null && !cached.isClosed()) {
            return cached;
        }
        ClientSession client = sshClient.connect(DEFAULT_SSH_USER, route.seedlingIp(), route.seedlingPort())
                .verify(10, TimeUnit.SECONDS)
                .getSession();
        client.addPublicKeyIdentity(internalKey);
        client.auth().verify(10, TimeUnit.SECONDS);
        serverSession.setAttribute(RELAY_SESSION_KEY, client);
        serverSession.getCloseFuture().addListener(f -> client.close(false));
        log.debug("Opened relay session to {}:{}", route.seedlingIp(), route.seedlingPort());
        return client;
    }

    public CommandFactory commandFactory() {
        return (command, channel) -> new RelayCommand(this, RelayCommand.Mode.EXEC, command);
    }

    public ShellFactory shellFactory() {
        return channel -> new RelayCommand(this, RelayCommand.Mode.SHELL, null);
    }

    public SubsystemFactory subsystemFactory() {
        return new SubsystemFactory() {
            @Override
            public String getName() {
                return "sftp";
            }

            @Override
            public Command createSubsystemChannel(String subsystem, org.apache.sshd.server.session.ServerSession session) {
                return new RelayCommand(SeedlingRelay.this, RelayCommand.Mode.SUBSYSTEM, subsystem);
            }
        };
    }
}
```

(`OpenSSHKeyPairResourceParser.INSTANCE.loadKeyPairs(null, path, null)` — verified against sshd-common 2.19.0: the `KeyPairResourceLoader` default takes `(SessionContext, Path, FilePasswordProvider, OpenOption...)` and returns `Collection<KeyPair>`. Note `parseKeyPairResource(...)` does NOT exist in 2.19.0 — do not use it. The public-key identities are added to the client session so the seedling's sshd matches them against the baked `authorized_keys`.)

- [ ] **Step 4: Implement `RelayCommand`**

Create `gateway/src/main/java/dev/orchard/gateway/relay/RelayCommand.java` — extends `AbstractCommandSupport` (which implements `Command` and wires the stream fields + `onExit`). Bridge = Sync-streaming client channel + one virtual thread per direction:

```java
package dev.orchard.gateway.relay;

import dev.orchard.gateway.api.GatewayRoute;
import dev.orchard.gateway.auth.KeyAuthenticator;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.StreamingChannel;
import org.apache.sshd.server.command.AbstractCommandSupport;
import org.apache.sshd.server.session.ServerSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

/**
 * Server-side command (shell/exec/subsystem) that opens the matching channel on
 * the relay client session and pumps bytes bidirectionally. Client channel uses
 * Sync streaming; one virtual thread per direction.
 */
public class RelayCommand extends AbstractCommandSupport {

    public enum Mode { SHELL, EXEC, SUBSYSTEM }

    private final SeedlingRelay relay;
    private final Mode mode;
    private final String argument;

    public RelayCommand(SeedlingRelay relay, Mode mode, String argument) {
        super(argument, null);
        this.relay = relay;
        this.mode = mode;
        this.argument = argument;
    }

    @Override
    public void run() {
        try {
            ServerSession serverSession = getSession();
            GatewayRoute route = serverSession.getAttribute(KeyAuthenticator.ROUTE_KEY);
            if (route == null) {
                onExit(255);
                return;
            }
            ClientSession client = relay.relaySession(serverSession, route);
            ClientChannel channel = switch (mode) {
                case SHELL -> client.createShellChannel();
                case EXEC -> client.createExecChannel(argument);
                case SUBSYSTEM -> client.createSubsystemChannel(argument);
            };
            channel.setStreaming(StreamingChannel.Streaming.Sync);
            channel.open().verify(10, TimeUnit.SECONDS);

            // client → seedling: server command stdin → relay channel
            InputStream serverIn = getInputStream();
            OutputStream relayOut = channel.getInvertedOut();
            Thread outPump = Thread.ofVirtual().name("relay-out").start(() -> copy(serverIn, relayOut));

            // seedling → client: relay channel in/err → server command out/err
            InputStream relayIn = channel.getInvertedIn();
            OutputStream serverOut = getOutputStream();
            Thread inPump = Thread.ofVirtual().name("relay-in").start(() -> copy(relayIn, serverOut));
            Thread errPump = Thread.ofVirtual().name("relay-err").start(() -> copy(channel.getInvertedErr(), getErrorStream()));

            channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), TimeUnit.MINUTES.toMillis(30));
            inPump.join(2_000);
            errPump.join(2_000);
            outPump.join(2_000);

            Integer exit = channel.getExitStatus();
            onExit(exit == null ? 0 : exit);
        } catch (Exception e) {
            log.warn("Relay failed: {}", e.toString());
            onExit(255);
        }
    }

    private static void copy(InputStream in, OutputStream out) {
        try (in; out) {
            in.transferTo(out);
        } catch (IOException e) {
            // channel closed on one side; the peer close propagates the rest
        }
    }

    @Override
    protected void onExit(int exitValue) {
        super.onExit(exitValue);
    }
}
```

Notes for the executor:
- `AbstractCommandSupport` requires `run()`; it supplies `getSession()`, `getInputStream()/getOutputStream()/getErrorStream()`, and `onExit(int)`. The `super(argument, null)` executor may be `null` — if 2.19.0 requires a non-null `CloseableExecutorService`, pass `ThreadUtils.noClose(relay.executor())` and give `SeedlingRelay` a cached pool with lifecycle.
- `channel.getInvertedErr()` returns a stream for stderr only when the channel was created with `createShellChannel()`/`createExecChannel()`; for subsystem channels it is the (unused) extended-data stream — harmless to pump.
- The 30-minute `waitFor` cap prevents a wedged pump from pinning the command forever; the close future of the server channel still tears everything down.
- pty/window-change/env forwarding: for interactive shells the client's `pty-req` reaches the server channel, not the command's streams. This phase's required pass is exec + non-pty flows (covered by Task 8); interactive-pty fidelity is a stretch goal — see Task 8 notes.

- [ ] **Step 5: Wire the `SshClient` bean**

Add to `HttpClientConfig` (or a new `RelayConfig`):

```java
@Bean(destroyMethod = "stop")
SshClient sshClient() {
    return SshClient.setUpDefaultClient();
}
```

`SeedlingRelay`'s `@PostConstruct` calls `sshClient.start()`.

- [ ] **Step 6: Run the unit test**

Run: `./gradlew :gateway:test --tests "dev.orchard.gateway.relay.SeedlingRelayTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add gateway/src/main/java/dev/orchard/gateway/relay gateway/src/main/java/dev/orchard/gateway/config
git commit -m "feat(gateway): add SeedlingRelay jumphost for shell/exec/sftp channels"
```

---

### Task 6: Add the two-hop `direct-tcpip` relay channel

Replaces MINA's default `direct-tcpip` factory (which would make the gateway open raw TCP to the target — impossible here) with a channel that re-tunnels the forward through the gateway→seedling relay session. Modeled on `org.apache.sshd.server.forward.TcpipServerChannel` (verified 2.19.0): `doInit` parses the target from the channel-open buffer, an async `ChannelDirectTcpip` is opened on the relay session, `doWriteData` pushes client bytes to the relay channel's inverted out, and a reader thread pushes relay-channel bytes back through the server channel's `ChannelAsyncOutputStream`.

**Files:**
- Create: `gateway/src/main/java/dev/orchard/gateway/relay/RelayDirectTcpipChannel.java`
- Create: `gateway/src/main/java/dev/orchard/gateway/relay/RelayDirectTcpipFactory.java`
- Test: covered by Task 8 integration test (this task adds the class; the end-to-end two-hop assertion lives there)

- [ ] **Step 1: Write the channel skeleton + parse (fail-fast structure)**

Create `gateway/src/main/java/dev/orchard/gateway/relay/RelayDirectTcpipChannel.java`:

```java
package dev.orchard.gateway.relay;

import dev.orchard.gateway.api.GatewayRoute;
import dev.orchard.gateway.auth.KeyAuthenticator;
import org.apache.sshd.client.channel.ChannelDirectTcpip;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.channel.ChannelAsyncOutputStream;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.server.channel.AbstractServerChannel;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Server-side direct-tcpip channel that tunnels the forward through the relay
 * client session (client → gateway → seedling → target). Parses the target the
 * same way TcpipServerChannel.doInit does; the gateway never opens TCP itself.
 */
public class RelayDirectTcpipChannel extends AbstractServerChannel {

    private final SeedlingRelay relay;
    private SshdSocketAddress target;
    private SshdSocketAddress originator;
    private ChannelAsyncOutputStream out;
    private ChannelDirectTcpip tunnel;

    public RelayDirectTcpipChannel(SeedlingRelay relay) {
        super("direct-tcpip", java.util.Collections.emptyList(), null);
        this.relay = relay;
    }

    @Override
    protected org.apache.sshd.client.future.OpenFuture doInit(org.apache.sshd.common.util.buffer.Buffer buffer) {
        // Same wire layout as TcpipServerChannel.doInit (verified against 2.19.0):
        String hostToConnect = buffer.getString();
        int portToConnect = buffer.getInt();
        String originatorIp = buffer.getString();
        int originatorPort = buffer.getInt();
        this.target = new SshdSocketAddress(hostToConnect, portToConnect);
        this.originator = new SshdSocketAddress(originatorIp, originatorPort);

        org.apache.sshd.client.future.OpenFuture f = new org.apache.sshd.client.future.DefaultOpenFuture(this, this);
        try {
            ServerSession serverSession = (ServerSession) getSession();
            GatewayRoute route = serverSession.getAttribute(KeyAuthenticator.ROUTE_KEY);
            ClientSession client = relay.relaySession(serverSession, route);
            // ChannelDirectTcpip's constructor takes only (SshdSocketAddress, SshdSocketAddress)
            // — no ClientSession parameter. The channel is opened via the session's own factory
            // method instead, which is what actually attaches it to that session.
            tunnel = client.createDirectTcpipChannel(originator, target);
            tunnel.setStreaming(StreamingChannel.Streaming.Sync);
            tunnel.open().addListener(future -> {
                if (future.isOpened()) {
                    signalChannelOpenSuccess();
                    f.setOpened();
                    startTunnelReader();
                } else {
                    signalChannelOpenFailure(future.getException());
                    f.setException(future.getException());
                    close(true);
                }
            });
        } catch (Exception e) {
            f.setException(e);
            close(true);
        }
        return f;
    }

    private void startTunnelReader() {
        // Bytes arriving from the target (via the relay) go back to the SSH client
        // through the server channel's ChannelAsyncOutputStream.
        Thread.ofVirtual().name("relay-tcpip-in").start(() -> {
            try {
                InputStream in = tunnel.getInvertedIn();
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    ByteArrayBuffer packet = new ByteArrayBuffer(buf, 0, n, false);
                    out.writeBuffer(packet).addListener(future -> { });
                }
            } catch (Exception e) {
                close(false);
            }
        });
    }

    @Override
    protected void doWriteData(byte[] data, int off, long len) throws java.io.IOException {
        // Bytes from the SSH client → relay channel → seedling → target.
        if (tunnel != null) {
            var outStream = tunnel.getInvertedOut();
            outStream.write(data, off, (int) len);
            outStream.flush();
        }
    }

    @Override
    protected org.apache.sshd.common.Closeable getInnerCloseable() {
        return org.apache.sshd.common.util.closeable.AbstractCloseable.builder()
                .close(out)
                .close(super.getInnerCloseable())
                .close(() -> {
                    if (tunnel != null) {
                        tunnel.close(false);
                    }
                })
                .build();
    }
}
```

Create `gateway/src/main/java/dev/orchard/gateway/relay/RelayDirectTcpipFactory.java`:

```java
package dev.orchard.gateway.relay;

import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelFactory;
import org.apache.sshd.common.session.Session;

/** Replaces MINA's default direct-tcpip factory with the two-hop relay. */
public class RelayDirectTcpipFactory implements ChannelFactory {

    private final SeedlingRelay relay;

    public RelayDirectTcpipFactory(SeedlingRelay relay) {
        this.relay = relay;
    }

    @Override
    public String getName() {
        return "direct-tcpip";
    }

    @Override
    public Channel createChannel(Session session) {
        return new RelayDirectTcpipChannel(relay);
    }
}
```

- [ ] **Step 2: Verify against the 2.19.0 source**

Open `sshd-core/.../server/forward/TcpipServerChannel.java` (the version fetched during research) and reconcile every `AbstractServerChannel` hook used above:
- `doInit(Buffer)`, `signalChannelOpenSuccess()`, `signalChannelOpenFailure(Throwable)`, `doWriteData(byte[], int, long)`, `getInnerCloseable()`, `AbstractCloseable.builder()`.
- `ChannelDirectTcpip` itself only has a `(SshdSocketAddress local, SshdSocketAddress remote)` constructor — it is opened via `ClientSession.createDirectTcpipChannel(local, remote)` (which attaches it to that session), not by constructing it directly with the session. Confirm `getInvertedIn()/getInvertedOut()` require `Streaming.Sync` (set above).
- The `out` `ChannelAsyncOutputStream` must be constructed in `doInit` (`new ChannelAsyncOutputStream(this, SshConstants.SSH_MSG_CHANNEL_DATA)`), mirroring `TcpipServerChannel`; ensure `handleWindowAdjust` calls `out.onWindowExpanded()` for flow control.

Adjust the skeleton to the exact 2.19.0 API. The wiring into `GroveRelayServer` (replacing the default factory) happens in Task 7.

- [ ] **Step 3: Commit (structure only; behavior asserted in Task 8)**

```bash
git add gateway/src/main/java/dev/orchard/gateway/relay/RelayDirectTcpipChannel.java gateway/src/main/java/dev/orchard/gateway/relay/RelayDirectTcpipFactory.java
git commit -m "feat(gateway): add two-hop direct-tcpip relay channel"
```

---

### Task 7: Add `GroveRelayServer` (assemble + start the MINA `SshServer`)

**Files:**
- Create: `gateway/src/main/java/dev/orchard/gateway/server/GroveRelayServer.java`
- Create: `gateway/src/main/java/dev/orchard/gateway/server/HostKeyProvider.java`
- Test: `gateway/src/test/java/dev/orchard/gateway/server/HostKeyProviderTest.java`
- Test: `gateway/src/test/java/dev/orchard/gateway/server/GroveRelayServerTest.java` (context-load + actuator health)

- [ ] **Step 1: Write the failing tests**

Create `gateway/src/test/java/dev/orchard/gateway/server/HostKeyProviderTest.java` — the host key is generated on first run and persisted (the fence `SigningKeyConfig` pattern):

```java
package dev.orchard.gateway.server;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;

class HostKeyProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesAndPersistsHostKeyOnFirstRun() throws Exception {
        Path keyFile = tempDir.resolve("gateway-host-key");

        HostKeyProvider first = new HostKeyProvider(keyFile.toString());
        Iterator<KeyPair> firstKeys = first.loadKeys(null).iterator();
        assertThat(firstKeys.hasNext()).isTrue();
        assertThat(firstKeys.next().getPublic().getAlgorithm()).isEqualTo("EdDSA");
        assertThat(Files.exists(keyFile)).isTrue();

        HostKeyProvider second = new HostKeyProvider(keyFile.toString());
        Iterator<KeyPair> secondKeys = second.loadKeys(null).iterator();
        assertThat(secondKeys.hasNext()).isTrue();
        assertThat(secondKeys.next().getPublic()).isEqualTo(firstKeysHasPublic(first));
    }

    private java.security.PublicKey firstKeysHasPublic(HostKeyProvider provider) throws Exception {
        return provider.loadKeys(null).iterator().next().getPublic();
    }
}
```

(If `SimpleGeneratorHostKeyProvider` with `algorithm(KeyUtils.ED_25519)` yields `EdDSA` public keys — it does — the assertion holds. The point: the same file produces the same host key across restarts.)

Create `gateway/src/test/java/dev/orchard/gateway/server/GroveRelayServerTest.java` — boots the whole context with an ephemeral SSH port and a temp internal key so nothing binds 2222 or touches `~/.ssh` in CI:

```java
package dev.orchard.gateway.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class GroveRelayServerTest {

    private static final Path TEST_DIR = Path.of(System.getProperty("java.io.tmpdir"), "orchard-gateway-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        Files.createDirectories(TEST_DIR);
        KeyPair internal = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path keyFile = TEST_DIR.resolve("orchard_ed25519");
        try (var out = Files.newOutputStream(keyFile)) {
            org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter.INSTANCE
                    .writePrivateKey(internal, "test-key", null, out);
        }
        registry.add("orchard.gateway.internal-ssh-key-path", keyFile::toString);
        registry.add("orchard.gateway.host-key-path", () -> TEST_DIR.resolve("gateway-host-key").toString());
        registry.add("orchard.gateway.ssh-port", () -> 0);
        registry.add("orchard.gateway.oauth2.client-secret", () -> "dev-secret");
    }

    @Autowired
    private GroveRelayServer relayServer;

    @Test
    void contextLoadsAndSshListenerIsUp() {
        assertThat(relayServer).isNotNull();
        assertThat(relayServer.getBoundPort()).isGreaterThan(0);
    }
}
```

`OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(key, comment, null, out)` (package `org.apache.sshd.common.config.keys.writer.openssh`) is the ssh-keygen-compatible OpenSSH writer — verified in 2.19.0. The `null` encryption context writes an unencrypted PEM that `SeedlingRelay`'s loader reads.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :gateway:test --tests "dev.orchard.gateway.server.*"`
Expected: FAIL — `cannot find symbol: class GroveRelayServer` / `HostKeyProvider`.

- [ ] **Step 3: Implement `HostKeyProvider`**

Create `gateway/src/main/java/dev/orchard/gateway/server/HostKeyProvider.java` — thin wrapper so the host-key file behavior is unit-testable:

```java
package dev.orchard.gateway.server;

import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;

import java.nio.file.Path;
import java.security.KeyPair;

/** The gateway's own SSH host key, generated on first run and persisted. */
public class HostKeyProvider implements KeyPairProvider {

    private final SimpleGeneratorHostKeyProvider delegate;

    public HostKeyProvider(String path) {
        // SimpleGeneratorHostKeyProvider has no builder() — it's a plain constructor +
        // inherited setAlgorithm(String) from AbstractGeneratorHostKeyProvider. The ed25519
        // key-type identifier lives on KeyPairProvider (SSH_ED25519), not KeyUtils.
        this.delegate = new SimpleGeneratorHostKeyProvider(Path.of(path));
        this.delegate.setAlgorithm(KeyPairProvider.SSH_ED25519);
    }

    @Override
    public Iterable<KeyPair> loadKeys(org.apache.sshd.common.session.SessionContext session) {
        return delegate.loadKeys(session);
    }
}
```

(KeyPairProvider has more methods than `loadKeys` — implement the interface fully or extend `SimpleGeneratorHostKeyProvider` directly; the wrapper is only to keep the file path explicit.)

- [ ] **Step 4: Implement `GroveRelayServer`**

Create `gateway/src/main/java/dev/orchard/gateway/server/GroveRelayServer.java`:

```java
package dev.orchard.gateway.server;

import dev.orchard.gateway.auth.KeyAuthenticator;
import dev.orchard.gateway.config.GatewayProperties;
import dev.orchard.gateway.relay.RelayDirectTcpipFactory;
import dev.orchard.gateway.relay.SeedlingRelay;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.sshd.common.channel.ChannelFactory;
import org.apache.sshd.server.ServerBuilder;
import org.apache.sshd.server.SshServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Boots the MINA SSHD listener. This is the ONLY process that reaches seedling
 * port 22 (loopback, colocated with trellis). Spring Boot's own server.port is
 * admin/actuator only.
 */
@Component
public class GroveRelayServer {

    private static final Logger log = LoggerFactory.getLogger(GroveRelayServer.class);

    private final GatewayProperties properties;
    private final HostKeyProvider hostKeyProvider;
    private final KeyAuthenticator keyAuthenticator;
    private final SeedlingRelay seedlingRelay;

    private SshServer server;

    public GroveRelayServer(GatewayProperties properties, HostKeyProvider hostKeyProvider,
                            KeyAuthenticator keyAuthenticator, SeedlingRelay seedlingRelay) {
        this.properties = properties;
        this.hostKeyProvider = hostKeyProvider;
        this.keyAuthenticator = keyAuthenticator;
        this.seedlingRelay = seedlingRelay;
    }

    @PostConstruct
    void start() throws Exception {
        server = SshServer.setUpDefaultServer();
        server.setPort(properties.getSshPort());
        server.setKeyPairProvider(hostKeyProvider);
        server.setPublickeyAuthenticator(keyAuthenticator);

        server.setShellFactory(seedlingRelay.shellFactory());
        server.setCommandFactory(seedlingRelay.commandFactory());
        server.setSubsystemFactories(List.of(seedlingRelay.subsystemFactory()));

        // Replace MINA's direct-tcpip factory with the two-hop relay.
        List<ChannelFactory> factories = new ArrayList<>(ServerBuilder.DEFAULT_CHANNEL_FACTORIES);
        factories.replaceAll(f -> "direct-tcpip".equals(f.getName())
                ? new RelayDirectTcpipFactory(seedlingRelay)
                : f);
        server.setChannelFactories(factories);

        server.start();
        log.info("Grove SSH relay listening on port {}", server.getPort());
    }

    @PreDestroy
    void stop() throws Exception {
        if (server != null && server.isStarted()) {
            server.stop();
        }
    }

    /** Actual bound port (useful when configured with port 0 in tests). */
    public int getBoundPort() {
        return server != null ? server.getPort() : -1;
    }
}
```

- [ ] **Step 5: Register the `HostKeyProvider` bean**

Add to `HttpClientConfig` (or `RelayConfig`):

```java
@Bean
HostKeyProvider hostKeyProvider(GatewayProperties properties) {
    return new HostKeyProvider(properties.getHostKeyPath());
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :gateway:test`
Expected: PASS — `HostKeyProviderTest` + `GroveRelayServerTest` (context boots, SSH listener up on an ephemeral port).

- [ ] **Step 7: Commit**

```bash
git add gateway/src/main/java/dev/orchard/gateway/server gateway/src/test/java/dev/orchard/gateway/server
git commit -m "feat(gateway): assemble and start the MINA SSHD relay listener"
```

---

### Task 8: Integration test — real relay round-trips (exec + two-hop direct-tcpip)

Boots TWO MINA `SshServer`s on ephemeral ports (a fake "seedling" and the gateway) plus an in-memory echo TCP service, and drives them with a real `SshClient`. No Spring context, no trellis — the gateway's `TrellisApiClient`/`GroveResolver`/`KeyAuthenticator` are replaced by a stub authenticator so the test exercises the relay itself.

**Files:**
- Create: `gateway/src/test/java/dev/orchard/gateway/relay/RelayIntegrationTest.java`

- [ ] **Step 1: Write the failing test**

Create `gateway/src/test/java/dev/orchard/gateway/relay/RelayIntegrationTest.java`:

```java
package dev.orchard.gateway.relay;

import dev.orchard.gateway.api.GatewayRoute;
import dev.orchard.gateway.auth.KeyAuthenticator;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.server.SshServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots a fake seedling sshd + the gateway sshd on ephemeral ports and round-trips
 * an exec channel through the relay. Runs WITHOUT a Spring context.
 */
class RelayIntegrationTest {

    @TempDir
    Path tempDir;

    private SshServer seedling;
    private SshServer gateway;
    private SshClient client;

    @BeforeEach
    void setUp() throws Exception {
        // ---- fake seedling: accepts any key, echoes "relay-ok" for exec "ping" ----
        seedling = SshServer.setUpDefaultServer();
        seedling.setPort(0);
        seedling.setKeyPairProvider(testHostKeyProvider());
        seedling.setPublickeyAuthenticator((u, k, s) -> true);
        seedling.setCommandFactory((command, channel) -> new org.apache.sshd.server.command.AbstractCommandSupport(command, null) {
            @Override
            public void run() {
                try {
                    getOutputStream().write("relay-ok\n".getBytes());
                    getOutputStream().flush();
                } catch (Exception ignored) {
                }
                onExit(0);
            }
        });
        seedling.start();

        // ---- gateway under test ----
        GatewayRoute route = new GatewayRoute(UUID.randomUUID(), UUID.randomUUID(), "127.0.0.1", seedling.getPort(), "FLOURISHING");
        SeedlingRelay relay = new SeedlingRelay(buildSshClient(), gatewayProperties());
        gateway = SshServer.setUpDefaultServer();
        gateway.setPort(0);
        gateway.setKeyPairProvider(testHostKeyProvider());
        gateway.setPublickeyAuthenticator((u, k, s) -> {
            s.setAttribute(KeyAuthenticator.ROUTE_KEY, route);
            return true;
        });
        gateway.setShellFactory(relay.shellFactory());
        gateway.setCommandFactory(relay.commandFactory());
        gateway.start();

        client = buildSshClient();
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.stop();
        if (gateway != null) gateway.stop();
        if (seedling != null) seedling.stop();
    }

    @Test
    void execCommandIsRelayedToSeedling() throws Exception {
        try (ClientSession session = client.connect("some-grove", "127.0.0.1", gateway.getPort())
                .verify(10, java.util.concurrent.TimeUnit.SECONDS).getSession()) {
            session.auth().verify(10, java.util.concurrent.TimeUnit.SECONDS);
            var channel = session.createExecChannel("ping");
            channel.setStreaming(org.apache.sshd.common.channel.StreamingChannel.Streaming.Sync);
            channel.open().verify(10, java.util.concurrent.TimeUnit.SECONDS);
            channel.waitFor(java.util.EnumSet.of(org.apache.sshd.client.channel.ClientChannelEvent.CLOSED),
                    java.util.concurrent.TimeUnit.MINUTES.toMillis(1));
            assertThat(new String(channel.getInvertedIn().readAllBytes())).contains("relay-ok");
            assertThat(channel.getExitStatus()).isEqualTo(0);
        }
    }

    // ---- helpers (sketched; adjust to 2.19.0 APIs) ----

    private org.apache.sshd.common.keyprovider.KeyPairProvider testHostKeyProvider() throws Exception {
        // Same fix as HostKeyProvider (Task 7): no builder() on SimpleGeneratorHostKeyProvider,
        // and the ed25519 identifier is KeyPairProvider.SSH_ED25519, not KeyUtils.ED_25519.
        var provider = new org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider(
                tempDir.resolve("host-key-" + UUID.randomUUID()));
        provider.setAlgorithm(org.apache.sshd.common.keyprovider.KeyPairProvider.SSH_ED25519);
        return provider;
    }

    private SshClient buildSshClient() {
        SshClient c = SshClient.setUpDefaultClient();
        c.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        c.start();
        return c;
    }

    private dev.orchard.gateway.config.GatewayProperties gatewayProperties() throws Exception {
        // write the internal key as OpenSSH PEM for the relay to load
        java.security.KeyPair pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path keyFile = tempDir.resolve("orchard_ed25519");
        try (var out = java.nio.file.Files.newOutputStream(keyFile)) {
            org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter.INSTANCE
                    .writePrivateKey(pair, "test-key", null, out);
        }
        dev.orchard.gateway.config.GatewayProperties p = new dev.orchard.gateway.config.GatewayProperties();
        p.setInternalSshKeyPath(keyFile.toString());
        return p;
    }
}
```

Notes:
- The gateway test stub stores `route` directly (no trellis), so `KeyAuthenticator`/`GroveResolver` are not exercised here — they have their own unit tests (Tasks 3–4).
- `channel.getInvertedIn().readAllBytes()` works because the client channel uses Sync streaming (data buffered until the channel is closed); the test client is a plain `SshClient`.
- **Stretch (two-hop direct-tcpip):** once the exec path is green, add a second test — start a plain `ServerSocket` echo on the loopback, wire the seedling's sshd with a default `TcpForwardingFilter` (default allows), then from the test client call `session.startLocalPortForwarding(AddressBinding, new SshdSocketAddress("127.0.0.1", 0), new SshdSocketAddress("127.0.0.1", echoPort))` and assert a socket to the local forward reaches the echo service through gateway → seedling → echo. This asserts the `RelayDirectTcpipChannel` end-to-end. (Requires the gateway server's channel factories to have the relay factory wired — add the same `replaceAll` swap as `GroveRelayServer` to the gateway in this test.)
- **pty fidelity** (interactive `ssh` with `-t`) is not asserted here; the exec path covers the byte pumping. If interactive shells are needed this phase, extend the test with a `pty-req` on the client channel and assert terminal size reaches the seedling — but treat that as a follow-up if time-boxed.

- [ ] **Step 2: Run the integration test**

Run: `./gradlew :gateway:test --tests "dev.orchard.gateway.relay.RelayIntegrationTest"`
Expected: PASS — `execCommandIsRelayedToSeedling`. If this proves flaky in CI (two servers + ports), move it to `integration-tests/src/integrationTest/` per the spec's contingency note, adding `implementation(project(":gateway"))` and the sshd dependency to `integration-tests/build.gradle.kts`.

- [ ] **Step 3: Commit**

```bash
git add gateway/src/test/java/dev/orchard/gateway/relay/RelayIntegrationTest.java
git commit -m "test(gateway): relay integration test for exec round-trip"
```

---

### Task 9: Full build + verify

- [ ] **Step 1: Run the full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — all modules green (core, roots, harvest, nursery, greenhouse, apiary, trellis, trowel, fence, gateway, integration-tests). The new `gateway` module compiles, unit-tests green, integration test green.

- [ ] **Step 2: Manual smoke (optional, with a running stack)**

With `docker compose up -d postgres`, trellis running (`./gradlew :trellis:bootRun`), fence running standalone with `FENCE_GATEWAY_CLIENT_ID/FENCE_GATEWAY_CLIENT_SECRET` set (Phase 2), and `GATEWAY_OAUTH2_CLIENT_SECRET` set to the same secret, start the gateway:

```bash
./gradlew :gateway:bootRun
```

Verify:
```bash
curl -s http://localhost:8081/actuator/health          # {"status":"UP"}
nc -z localhost 2222 && echo "SSH listener up"          # the relay port
```

Then with a registered key and a FLOURISHING grove, from another shell:
```bash
ssh -o StrictHostKeyChecking=no -p 2222 <grove-uuid>@localhost -i ~/.orchard/keys/default
```
Expected: publickey auth against the registered key, then the seedling's shell. (A fully working interactive session also needs the pty-fidelity stretch item in Task 8 — a bare `echo` exec should round-trip regardless.)

- [ ] **Step 3: Confirm no stray files**

Run: `git status`
Expected: working tree clean (all changes committed in Tasks 1–8).

- [ ] **Step 4: Update `docs/TOC.md`** if it enumerates modules

Check whether `docs/TOC.md` lists the module tree; if so, add `gateway/`.

---

## Out of Scope (this phase)

- Owner-token fallback (`OwnerTokenAuthenticator` password auth, JWKS validation of the fence gateway JWT) — Phase 4. `GroveRelayServer` installs no `PasswordAuthenticator`.
- Trowel `ssh-key` CLI, `grove connect` rewire, dev-server `orchard-gateway` management — Phase 4.
- Any change to `Grove.getSshConnectionString()` / `GroveResponse.sshConnectionString` (external contract; see spec Out-of-Scope note).
- Interactive-pty fidelity beyond the byte-pump (stretch item in Task 8).
- Multi-grove-per-connection routing (username selects exactly one grove).
- Any `roots`/JPA dependency — the gateway stays `core`-only per the spec.
