# Grove SSH Gateway — Fence Gateway-Token Endpoint Implementation Plan (Phase 2 of 4)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the fence-side owner-token endpoint (`POST /gateway-token`) that mints a short-lived (5 min) SSH gateway JWT from a valid fence-issued user access token, plus the confidential `orchard-gateway` OAuth2 client the gateway uses to mint its own service token for trellis calls.

**Architecture:** Phase 2 of 4 (see `docs/superpowers/specs/2026-08-03-grove-ssh-gateway-design.md` §2e, §4). A new `@Order(0)` `SecurityFilterChain` matches `/gateway-token` and validates the presented bearer token **locally** against fence's own `JWKSet` (no JWKS fetch). `GatewayTokenService` signs the gateway JWT with the existing `SigningKeyConfig` RSA key. `RegisteredClientRepositoryConfig` gains a confidential `orchard-gateway` client (`client_credentials`, `CLIENT_SECRET_BASIC`). `TokenClaimsConfig` is **not** modified.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring Authorization Server, Spring Security 7 (resource server + `oauth2ResourceServer`), Nimbus JOSE+JWT, JUnit 5, AssertJ, MockMvc.

---

## Env-var naming note (resolves a spec ambiguity)

The spec names two env vars that must hold the **same value** but are read by two different processes:

| Process reads it | Env var | Property |
|------------------|---------|----------|
| fence | `FENCE_GATEWAY_CLIENT_SECRET` | `fence.gateway-client.client-secret` (the registered client's secret) |
| gateway (Phase 3) | `GATEWAY_OAUTH2_CLIENT_SECRET` | `orchard.gateway.oauth2.client-secret` (its own credential) |

Each process names its env var after itself, per the existing `FENCE_*` / `GATEWAY_*` convention. They are not conflicting; both must be set to the same value in deployment. The dev-server (Phase 4) passes both through.

---

### Task 1: Register the confidential `orchard-gateway` client

**Files:**
- Create: `fence/src/main/java/dev/orchard/fence/security/FenceGatewayClientProperties.java`
- Modify: `fence/src/main/java/dev/orchard/fence/security/RegisteredClientRepositoryConfig.java`
- Modify: `fence/src/main/resources/application.yml` (under `fence:`)
- Modify: `fence/src/test/java/dev/orchard/fence/security/RegisteredClientRepositoryConfigTest.java`

- [ ] **Step 1: Write the failing test**

Append a new test to `RegisteredClientRepositoryConfigTest` and update its `contextRunner` so the new `FenceGatewayClientProperties` bean is provided (the `ApplicationContextRunner` fails if a required bean is missing). Replace the whole file with:

```java
package dev.orchard.fence.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import static org.assertj.core.api.Assertions.assertThat;

class RegisteredClientRepositoryConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RegisteredClientRepositoryConfig.class))
            .withBean(FenceClientProperties.class, () -> {
                FenceClientProperties props = new FenceClientProperties();
                props.setClientId("orchard-ui");
                props.setRedirectUri("http://localhost:3000/callback");
                return props;
            })
            .withBean(FenceGatewayClientProperties.class, () -> {
                FenceGatewayClientProperties props = new FenceGatewayClientProperties();
                props.setClientId("orchard-gateway");
                props.setClientSecret("dev-secret");
                return props;
            });

    @Test
    void trowelCliIsRegisteredForDeviceFlow() {
        contextRunner.run(context -> {
            RegisteredClientRepository repo = context.getBean(RegisteredClientRepository.class);
            RegisteredClient client = repo.findByClientId("trowel-cli");

            assertThat(client).isNotNull();
            assertThat(client.getClientAuthenticationMethods()).contains(ClientAuthenticationMethod.NONE);
            assertThat(client.getAuthorizationGrantTypes()).contains(
                    AuthorizationGrantType.DEVICE_CODE,
                    AuthorizationGrantType.REFRESH_TOKEN);
            assertThat(client.getScopes()).contains("openid");
        });
    }

    @Test
    void orchardUiIsRegisteredForAuthorizationCodeFlow() {
        contextRunner.run(context -> {
            RegisteredClientRepository repo = context.getBean(RegisteredClientRepository.class);
            RegisteredClient client = repo.findByClientId("orchard-ui");

            assertThat(client).isNotNull();
            assertThat(client.getClientAuthenticationMethods()).contains(ClientAuthenticationMethod.NONE);
            assertThat(client.getAuthorizationGrantTypes()).contains(
                    AuthorizationGrantType.AUTHORIZATION_CODE,
                    AuthorizationGrantType.REFRESH_TOKEN);
            assertThat(client.getScopes()).contains("openid", "profile", "email");
        });
    }

    @Test
    void orchardGatewayIsRegisteredForClientCredentials() {
        contextRunner.run(context -> {
            RegisteredClientRepository repo = context.getBean(RegisteredClientRepository.class);
            RegisteredClient client = repo.findByClientId("orchard-gateway");

            assertThat(client).isNotNull();
            assertThat(client.getClientAuthenticationMethods()).contains(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
            assertThat(client.getAuthorizationGrantTypes()).contains(AuthorizationGrantType.CLIENT_CREDENTIALS);
            assertThat(client.getClientSecret()).startsWith("{noop}");
            assertThat(client.getClientSecret()).endsWith("dev-secret");
        });
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :fence:test --tests "dev.orchard.fence.security.RegisteredClientRepositoryConfigTest"`
Expected: FAIL — test compilation error `cannot find symbol: class FenceGatewayClientProperties`

- [ ] **Step 3: Create `FenceGatewayClientProperties`**

Create `fence/src/main/java/dev/orchard/fence/security/FenceGatewayClientProperties.java`:

```java
package dev.orchard.fence.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fence.gateway-client")
public class FenceGatewayClientProperties {

    private String clientId;
    private String clientSecret;

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }
}
```

It is auto-registered via `@ConfigurationPropertiesScan` on `FenceApplication`.

- [ ] **Step 4: Register the client in `RegisteredClientRepositoryConfig`**

Replace `RegisteredClientRepositoryConfig.java` with:

```java
package dev.orchard.fence.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

import java.util.UUID;

@Configuration
public class RegisteredClientRepositoryConfig {

    @Bean
    RegisteredClientRepository registeredClientRepository(
            FenceClientProperties clientProperties,
            FenceGatewayClientProperties gatewayClientProperties) {

        // Both clients are public (no client secret): trowel-cli is a CLI that can't
        // safely hold a secret, and orchard-ui runs entirely in the browser. Neither
        // can authenticate with CLIENT_SECRET_BASIC, so both use NONE.
        RegisteredClient trowelCli = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("trowel-cli")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.DEVICE_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .scope("openid")
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .build())
                .build();

        RegisteredClient orchardUi = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientProperties.getClientId())
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(clientProperties.getRedirectUri())
                .scope("openid")
                .scope("profile")
                .scope("email")
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .requireProofKey(true)
                        .build())
                .build();

        // Confidential client: the SSH gateway authenticates with client_id +
        // client_secret (CLIENT_SECRET_BASIC) to mint its own client_credentials
        // service token for trellis /api/gateway/** calls. The secret must equal
        // the gateway's own GATEWAY_OAUTH2_CLIENT_SECRET in deployment.
        RegisteredClient orchardGateway = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(gatewayClientProperties.getClientId())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientSecret("{noop}" + gatewayClientProperties.getClientSecret())
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("openid")
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .build())
                .build();

        return new InMemoryRegisteredClientRepository(trowelCli, orchardUi, orchardGateway);
    }
}
```

- [ ] **Step 5: Add the application.yml defaults**

In `fence/src/main/resources/application.yml`, extend the existing `fence:` block (after `client:` / before `upstream:`) with:

```yaml
  gateway-client:
    client-id: ${FENCE_GATEWAY_CLIENT_ID:orchard-gateway}
    client-secret: ${FENCE_GATEWAY_CLIENT_SECRET:}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :fence:test --tests "dev.orchard.fence.security.RegisteredClientRepositoryConfigTest"`
Expected: PASS (3 tests)

- [ ] **Step 7: Commit**

```bash
git add fence/src/main/java/dev/orchard/fence/security/FenceGatewayClientProperties.java fence/src/main/java/dev/orchard/fence/security/RegisteredClientRepositoryConfig.java fence/src/main/resources/application.yml fence/src/test/java/dev/orchard/fence/security/RegisteredClientRepositoryConfigTest.java
git commit -m "feat(fence): register confidential orchard-gateway client for client_credentials"
```

---

### Task 2: Mint short-lived gateway tokens (`GatewayTokenService`)

**Files:**
- Create: `fence/src/main/java/dev/orchard/fence/gateway/GatewayTokenService.java`
- Test: `fence/src/test/java/dev/orchard/fence/gateway/GatewayTokenServiceTest.java`

- [ ] **Step 1: Write the failing unit tests**

Create `fence/src/test/java/dev/orchard/fence/gateway/GatewayTokenServiceTest.java`:

```java
package dev.orchard.fence.gateway;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayTokenServiceTest {

    private GatewayTokenService service;

    @BeforeEach
    void setUp() throws JOSEException {
        RSAKey rsaKey = new RSAKeyGenerator(2048).generate();
        service = new GatewayTokenService(new JWKSet(rsaKey));
    }

    @Test
    void mintsTokenWithGatewayClaims() throws Exception {
        SignedJWT jwt = SignedJWT.parse(service.mintGatewayToken("user-123", "dev@orchard.dev"));

        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("user-123");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("email")).isEqualTo("dev@orchard.dev");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("scope")).isEqualTo("gateway-ssh");
        assertThat(jwt.getJWTClaimsSet().getAudience()).contains("orchard-gateway");
        assertThat(jwt.getJWTClaimsSet().getJWTID()).isNotBlank();
    }

    @Test
    void mintsTokenExpiringWithinFiveMinutes() throws Exception {
        SignedJWT jwt = SignedJWT.parse(service.mintGatewayToken("user-123", "dev@orchard.dev"));

        long nowSeconds = System.currentTimeMillis() / 1000;
        long exp = jwt.getJWTClaimsSet().getExpirationTime().toInstant().getEpochSecond();
        assertThat(exp).isGreaterThan(nowSeconds);
        assertThat(exp).isLessThanOrEqualTo(nowSeconds + GatewayTokenService.TTL_SECONDS);
    }

    @Test
    void rejectsMissingEmail() {
        assertThatThrownBy(() -> service.mintGatewayToken("user-123", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");
    }

    @Test
    void rejectsBlankEmail() {
        assertThatThrownBy(() -> service.mintGatewayToken("user-123", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :fence:test --tests "dev.orchard.fence.gateway.GatewayTokenServiceTest"`
Expected: FAIL — `cannot find symbol: class GatewayTokenService`

- [ ] **Step 3: Implement `GatewayTokenService`**

Create `fence/src/main/java/dev/orchard/fence/gateway/GatewayTokenService.java`:

```java
package dev.orchard.fence.gateway;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.UUID;

/**
 * Mints the short-lived JWT the SSH gateway accepts as an SSH password
 * (OwnerTokenAuthenticator). Claims per design spec §2e:
 * {sub, email, scope: gateway-ssh, aud: orchard-gateway, exp, iat, jti}.
 */
@Service
public class GatewayTokenService {

    public static final long TTL_SECONDS = 300;

    private final RSAKey signingKey;

    public GatewayTokenService(JWKSet jwkSet) {
        // JWKSet has no toJWK() method — SigningKeyConfig always produces a set with exactly
        // one RSA key, so take it directly from getKeys().
        JWK jwk = jwkSet.getKeys().get(0);
        if (!(jwk instanceof RSAKey rsaKey) || rsaKey.isPrivate() == Boolean.FALSE) {
            throw new IllegalStateException("Signing key must be a private RSA key");
        }
        this.signingKey = rsaKey;
    }

    public String mintGatewayToken(String subject, String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email claim required");
        }
        long nowSeconds = System.currentTimeMillis() / 1000;
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("email", email)
                .claim("scope", "gateway-ssh")
                .audience("orchard-gateway")
                .issueTime(new Date(nowSeconds * 1000))
                .expirationTime(new Date((nowSeconds + TTL_SECONDS) * 1000))
                .jwtID(UUID.randomUUID().toString())
                .build();

        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(signingKey.getKeyID())
                            .type(com.nimbusds.jose.JOSEObjectType.JWT)
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign gateway token", e);
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :fence:test --tests "dev.orchard.fence.gateway.GatewayTokenServiceTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add fence/src/main/java/dev/orchard/fence/gateway/GatewayTokenService.java fence/src/test/java/dev/orchard/fence/gateway/GatewayTokenServiceTest.java
git commit -m "feat(fence): mint short-lived gateway tokens with gateway-ssh scope"
```

---

### Task 3: Add `POST /gateway-token` endpoint + local-JWKS security chain

**Files:**
- Create: `fence/src/main/java/dev/orchard/fence/gateway/GatewayTokenResponse.java`
- Create: `fence/src/main/java/dev/orchard/fence/gateway/GatewayTokenController.java`
- Create: `fence/src/main/java/dev/orchard/fence/security/GatewayTokenSecurityConfig.java`
- Test: `fence/src/test/java/dev/orchard/fence/gateway/GatewayTokenControllerTest.java`

- [ ] **Step 1: Write the failing endpoint tests**

Create `fence/src/test/java/dev/orchard/fence/gateway/GatewayTokenControllerTest.java`:

```java
package dev.orchard.fence.gateway;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the full fence context in standalone mode against a temp signing key so
 * the app's own JWKSet can both sign the test access token and validate it.
 */
@SpringBootTest(properties = "fence.signing-key.path=${java.io.tmpdir}/fence-gateway-token-test/signing-key.jwk")
@ActiveProfiles("standalone")
@AutoConfigureMockMvc
class GatewayTokenControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JWKSet jwkSet;

    private String signAccessToken(String subject, String email) throws JOSEException {
        RSAKey signingKey = (RSAKey) jwkSet.toJWK();
        long nowSeconds = System.currentTimeMillis() / 1000;
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("email", email)
                .issuer("http://localhost:7779")
                .audience("orchard-ui")
                .issueTime(new Date(nowSeconds * 1000))
                .expirationTime(new Date((nowSeconds + 3600) * 1000))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    @Test
    void validBearerMintsShortLivedGatewayToken() throws Exception {
        String accessToken = signAccessToken("user-123", "dev@orchard.dev");

        String body = mockMvc.perform(post("/gateway-token")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String gatewayToken = com.jayway.jsonpath.JsonPath.read(body, "$.token");
        SignedJWT jwt = SignedJWT.parse(gatewayToken);
        long nowSeconds = System.currentTimeMillis() / 1000;

        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("user-123");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("email")).isEqualTo("dev@orchard.dev");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("scope")).isEqualTo("gateway-ssh");
        assertThat(jwt.getJWTClaimsSet().getAudience()).contains("orchard-gateway");
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant().getEpochSecond())
                .isLessThanOrEqualTo(nowSeconds + GatewayTokenService.TTL_SECONDS);
    }

    @Test
    void missingBearerReturns401() throws Exception {
        mockMvc.perform(post("/gateway-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidBearerReturns401() throws Exception {
        mockMvc.perform(post("/gateway-token")
                        .header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :fence:test --tests "dev.orchard.fence.gateway.GatewayTokenControllerTest"`
Expected: FAIL — `validBearerMintsShortLivedGatewayToken` gets 404 (no controller); the 401 cases pass because the default chain denies unauthenticated requests. The endpoint test is the red one.

- [ ] **Step 3: Implement the controller + response**

Create `fence/src/main/java/dev/orchard/fence/gateway/GatewayTokenResponse.java`:

```java
package dev.orchard.fence.gateway;

public record GatewayTokenResponse(String token) {
}
```

Create `fence/src/main/java/dev/orchard/fence/gateway/GatewayTokenController.java`:

```java
package dev.orchard.fence.gateway;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class GatewayTokenController {

    private final GatewayTokenService gatewayTokenService;

    public GatewayTokenController(GatewayTokenService gatewayTokenService) {
        this.gatewayTokenService = gatewayTokenService;
    }

    /**
     * Mints a short-lived gateway JWT from the caller's fence-issued access
     * token. The bearer token was already validated by GatewayTokenSecurityConfig
     * against fence's own key; here we only project sub + email forward.
     */
    @PostMapping("/gateway-token")
    public GatewayTokenResponse gatewayToken(@AuthenticationPrincipal Jwt jwt) {
        try {
            return new GatewayTokenResponse(
                    gatewayTokenService.mintGatewayToken(jwt.getSubject(), jwt.getClaimAsString("email")));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Add the `@Order(0)` security chain with a local-JWKS decoder**

Create `fence/src/main/java/dev/orchard/fence/security/GatewayTokenSecurityConfig.java`:

```java
package dev.orchard.fence.security;

import com.nimbusds.jose.proc.JWSAlgorithmFamilyJWSKeySelector;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Validates bearer tokens presented to /gateway-token locally against fence's
 * own JWKSet (the tokens were issued by fence itself). Ordered @Order(0), ahead
 * of the OAuth2 authorization-server chain (@Order(1)), so /gateway-token never
 * falls through to the interactive endpoints matcher.
 */
@Configuration
public class GatewayTokenSecurityConfig {

    @Bean
    @Order(0)
    SecurityFilterChain gatewayTokenSecurityFilterChain(
            HttpSecurity http, JwtDecoder gatewayJwtDecoder) throws Exception {
        http
                .securityMatcher("/gateway-token")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(gatewayJwtDecoder)));

        return http.build();
    }

    @Bean
    JwtDecoder gatewayJwtDecoder(JWKSet jwkSet, FenceProperties properties) {
        JWSKeySelector<SecurityContext> selector =
                JWSAlgorithmFamilyJWSKeySelector.fromJWKSet(new ImmutableJWKSet<>(jwkSet));
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(selector);
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.getIssuer()));
        return decoder;
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :fence:test --tests "dev.orchard.fence.gateway.GatewayTokenControllerTest"`
Expected: PASS (3 tests)

- [ ] **Step 6: Run the full fence test suite**

Run: `./gradlew :fence:test`
Expected: PASS — all fence tests including `StandaloneAuthTest`, `FenceApplicationTests`, `CorsConfigTest`, `AuthorizationServerConfigTest`, `SigningKeyConfigTest`.

- [ ] **Step 7: Commit**

```bash
git add fence/src/main/java/dev/orchard/fence/gateway/GatewayTokenResponse.java fence/src/main/java/dev/orchard/fence/gateway/GatewayTokenController.java fence/src/main/java/dev/orchard/fence/security/GatewayTokenSecurityConfig.java fence/src/test/java/dev/orchard/fence/gateway/GatewayTokenControllerTest.java
git commit -m "feat(fence): add POST /gateway-token guarded by local-JWKS bearer validation"
```

---

### Task 4: Full build + verify

- [ ] **Step 1: Run the full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — all modules green (fence unchanged behavior elsewhere; no other module consumes these beans).

- [ ] **Step 2: Manual smoke (optional, with `docker compose up -d postgres` + trellis running)**

With fence running standalone (`--spring.profiles.active=standalone`) and the gateway client secret set, a `client_credentials` token for `orchard-gateway` can be minted via:

```bash
curl -s -X POST http://localhost:7779/oauth2/token \
  -u orchard-gateway:dev-secret \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials&scope=openid'
```

Expected: JSON with an `access_token` whose `aud` contains `orchard-gateway`. This token is what the Phase 3 gateway presents to trellis.

- [ ] **Step 3: Confirm no stray files**

Run: `git status`
Expected: working tree clean (all changes committed in Tasks 1–3).

---

## Out of Scope (this phase)

- The SSH gateway module (MINA SSHD, authenticators, relay) — Phase 3.
- Trowel `ssh-key` CLI, connect rewire, dev-server — Phase 4.
- Any change to `TokenClaimsConfig` (OidcUser-only customizer stays untouched).
- Issuer / JWKS fetching from a remote source — validation is intentionally local.
