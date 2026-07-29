package dev.orchard.e2e;

import dev.orchard.fence.FenceApplication;
import dev.orchard.trellis.OrchardApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full device-flow login round trip across fence and trellis, booted as separate
 * embedded Spring Boot apps in one JVM ({@code @SpringBootTest} only supports one
 * context per test class).
 * <p>
 * fence's issuer and default port (7779) are hardcoded in
 * {@code AuthorizationServerConfig}/{@code application.yml} rather than configurable
 * per-instance, so fence is booted on its real default port here rather than a random
 * one; trellis's {@code devserver} profile issuer-uri is hardcoded to match.
 */
class FenceLoginE2ETest {

    private static ConfigurableApplicationContext fenceContext;
    private static ConfigurableApplicationContext trellisContext;
    private static int trellisPort;
    private static final TestRestTemplate restTemplate = new TestRestTemplate();

    @BeforeAll
    static void startApps() {
        // The combined integration-tests classpath includes trellis's JPA/Flyway/Postgres
        // dependencies, which Spring Boot's classpath-based autoconfiguration would otherwise
        // try to activate for fence too (fence has no database of its own).
        // fence and trellis both bundle an application.yml at the same classpath
        // location; on this combined classpath only trellis's is visible (it shadows
        // fence's), so every fence.* property normally supplied by fence's own
        // application.yml must be set explicitly here instead of relying on it.
        // These must be passed as command-line-style args (not .properties(), which
        // maps to Spring Boot's lowest-precedence "default properties" source) so they
        // actually win over whichever application.yml ends up on the classpath.
        fenceContext = new SpringApplicationBuilder(FenceApplication.class)
                .run(
                        "--server.port=7779",
                        "--spring.security.oauth2.client.registration.google.client-id=test-google-client-id",
                        "--spring.security.oauth2.client.registration.google.client-secret=test-google-client-secret",
                        "--spring.security.oauth2.client.registration.google.scope=openid,profile,email",
                        "--spring.security.oauth2.client.provider.google.issuer-uri=https://accounts.google.com",
                        "--fence.issuer=http://localhost:7779",
                        "--fence.signing-key.path=${java.io.tmpdir}/orchard-fence-e2e-test/signing-key.jwk",
                        "--fence.client.client-id=orchard-ui",
                        "--fence.client.redirect-uri=http://localhost:3000/callback",
                        "--fence.upstream.registration-id=google",
                        "--logging.level.org.springframework.security=DEBUG",
                        "--spring.autoconfigure.exclude=" +
                                "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration," +
                                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration");

        // This test only exercises the auth flow, not grove/VM lifecycle. The
        // e2etest profile's configured base image doesn't exist, and the directory
        // it lives in is owned by the host's real orchard service account (not
        // writable by this test process), so point at the already-provisioned
        // image instead of letting auto-provisioning try to download one.
        trellisContext = new SpringApplicationBuilder(OrchardApplication.class)
                .profiles("devserver", "e2etest")
                .run("--server.port=0",
                        "--orchard.qemu.auto-provision=false",
                        "--orchard.qemu.base-image-path=/tmp/orchard/images/base.qcow2");
        trellisPort = Integer.parseInt(trellisContext.getEnvironment().getProperty("local.server.port"));
    }

    @AfterAll
    static void stopApps() {
        if (trellisContext != null) {
            trellisContext.close();
        }
        if (fenceContext != null) {
            fenceContext.close();
        }
    }

    @Test
    void trowelCliDeviceFlowLoginIssuesTokenValidatedByTrellis() throws Exception {
        // Step 1: request a device code from fence, as trowel's FenceClient does.
        HttpHeaders formHeaders = new HttpHeaders();
        formHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> deviceAuthBody = new LinkedMultiValueMap<>();
        deviceAuthBody.add("client_id", "trowel-cli");

        ResponseEntity<Map> deviceAuthResponse = restTemplate.postForEntity(
                "http://localhost:7779/oauth2/device_authorization",
                new HttpEntity<>(deviceAuthBody, formHeaders),
                Map.class);
        assertThat(deviceAuthResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String deviceCode = (String) deviceAuthResponse.getBody().get("device_code");
        String userCode = (String) deviceAuthResponse.getBody().get("user_code");
        assertThat(deviceCode).isNotBlank();
        assertThat(userCode).isNotBlank();

        // Step 2: simulate the resource owner approving the device code, authenticated
        // as if they'd already completed the real Google redirect (which this test
        // cannot drive headlessly).
        WebApplicationContext fenceWebContext = (WebApplicationContext) fenceContext;
        MockMvc fenceMockMvc = MockMvcBuilders.webAppContextSetup(fenceWebContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        fenceMockMvc.perform(post("/oauth2/device_verification")
                        .param("user_code", userCode)
                        .with(SecurityMockMvcRequestPostProcessors.oidcLogin()
                                .idToken(token -> token
                                        .subject("117501184000000000000")
                                        .claim("email", "cultivator@example.com")
                                        .claim("name", "Cultivator Example"))))
                .andExpect(status().is3xxRedirection());

        // Step 3: poll the token endpoint, as FenceClient does.
        MultiValueMap<String, String> tokenBody = new LinkedMultiValueMap<>();
        tokenBody.add("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
        tokenBody.add("device_code", deviceCode);
        tokenBody.add("client_id", "trowel-cli");

        ResponseEntity<Map> tokenResponse = restTemplate.postForEntity(
                "http://localhost:7779/oauth2/token",
                new HttpEntity<>(tokenBody, formHeaders),
                Map.class);
        assertThat(tokenResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String accessToken = (String) tokenResponse.getBody().get("access_token");
        assertThat(accessToken).isNotBlank();

        // Step 4: use the fence-issued token against trellis's /api/me.
        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth(accessToken);

        ResponseEntity<Map> meResponse = restTemplate.exchange(
                "http://localhost:" + trellisPort + "/api/me",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders),
                Map.class);
        assertThat(meResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(meResponse.getBody()).containsEntry("provider", "google");
        assertThat(meResponse.getBody()).containsEntry("email", "cultivator@example.com");
    }
}
