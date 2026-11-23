package dev.orchard.e2e;

import dev.orchard.api.dto.GroveResponse;
import dev.orchard.api.service.GroveService;
import dev.orchard.nursery.ProviderRegistry;
import dev.orchard.trellis.OrchardApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static dev.orchard.e2e.E2ETestConstants.E2E_SECRET_NAME;
import static dev.orchard.e2e.GroveClient.runOnSeedling;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = OrchardApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
@ActiveProfiles({"devserver", "e2etest"})
@ExtendWith({QemuPrerequisiteExtension.class, OutputCaptureExtension.class})
class GroveLifecycleE2ETest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GroveService groveService;

    @Autowired
    private ProviderRegistry providers;

    @LocalServerPort
    private int port;

    private GroveClient groves;

    @BeforeEach
    void connect() {
        groves = new GroveClient("http://localhost:" + port);
    }

    @Test
    void serverIsHealthy() {
        ResponseEntity<Map> health = restTemplate.getForEntity("/api/health", Map.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).containsEntry("status", "healthy");

        ResponseEntity<Map> ready = restTemplate.getForEntity("/api/health/ready", Map.class);
        assertThat(ready.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ready.getBody()).containsEntry("status", "ready");
    }

    @Test
    @Timeout(value = 40, unit = TimeUnit.MINUTES)
    void plantedGroveFlourishesServesSshAndClears(CapturedOutput output) throws Exception {
        String secret = "e2e-" + UUID.randomUUID();
        try {
            groves.putSecret(E2E_SECRET_NAME, secret);
            UUID groveId = groves.plant();
            try {
                exerciseSecretLifecycle(groveId, secret, output);
            } finally {
                groves.clearQuietly(groveId);
            }
        } finally {
            groves.deleteSecretQuietly(E2E_SECRET_NAME);
        }
    }

    private void exerciseSecretLifecycle(UUID groveId, String secret, CapturedOutput output) throws Exception {
        GroveResponse flourishing;
        int sshPort;
        try (var probe = SecretLeakProbe.onHostAndSeedling(secret, () -> sshPortOf(groveId))) {
            flourishing = awaitFlourishing(groveId);
            sshPort = flourishing.plot().sshPort();
            assertSecretDelivered(groveId, sshPort, secret, "flourishing");
        }
        assertSeedlingDiskFree(sshPort, secret);
        SecretLeakProbe.assertRowsFree(jdbc, secret, "flourishing");
        SecretLeakProbe.assertApiFree(restTemplate, groveId, secret, "flourishing");

        assertSshConfigAvailable(groveId, sshPort);
        assertThat(runOnSeedling(sshPort, "echo orchard-e2e-ok")).as("ssh: output").contains("orchard-e2e-ok");
        assertGroveListed(groveId);

        runOnSeedling(sshPort, "echo kept > /workspace/.orchard-e2e-marker");
        groves.stopAndAwaitDormant(groveId);
        GroveResponse resumed;
        try (var probe = SecretLeakProbe.onHostAndSeedling(secret, () -> sshPortOf(groveId))) {
            groves.start(groveId);
            resumed = awaitFlourishing(groveId);
            assertSecretDelivered(groveId, resumed.plot().sshPort(), secret, "start");
        }
        assertThat(resumed.fruits().getFirst().containerId()).as("start: same container")
            .isEqualTo(flourishing.fruits().getFirst().containerId());
        assertThat(runOnSeedling(resumed.plot().sshPort(), "cat /workspace/.orchard-e2e-marker"))
            .as("start: workspace file survived stop/start").contains("kept");
        SecretLeakProbe.assertRowsFree(jdbc, secret, "start");
        SecretLeakProbe.assertApiFree(restTemplate, groveId, secret, "start");

        assertThat(groves.get(groveId).missingSecrets()).as("warning: none while stored").isEmpty();
        groves.deleteSecret(E2E_SECRET_NAME);
        assertThat(groves.get(groveId).missingSecrets().getFirst().name()).as("warning: missing after delete")
            .isEqualTo(E2E_SECRET_NAME);
        groves.stopAndAwaitDormant(groveId);
        groves.start(groveId);
        GroveResponse unset = awaitFlourishing(groveId);
        assertThat(FruitShell.of(groveService, providers, groveId).loginEnv(E2E_SECRET_NAME))
            .as("unset: fruit login shell sees no value").isEmpty();
        assertThat(runOnSeedling(unset.plot().sshPort(), "bash -lc 'printf %s \"$ORCHARD_E2E_SECRET\"'"))
            .as("unset: seedling login shell sees no value").isEmpty();

        groves.clearAndAwaitCleared(groveId);
        assertThat(output.getAll()).as("trellis log: injection logged, value absent")
            .contains("Injected secrets [" + E2E_SECRET_NAME + "]")
            .doesNotContain(secret);
    }

    @AfterAll
    static void killStrayVmsAndScratch() {
        GroveClient.killStrayVmsAndScratch();
    }

    private GroveResponse awaitFlourishing(UUID groveId) {
        GroveResponse resp = groves.awaitFlourishing(groveId, "flourishing");
        assertThat(resp.plot().host()).as("flourishing: plot host").isEqualTo("127.0.0.1");
        assertThat(resp.fruits()).as("flourishing: fruits").isNotEmpty();
        assertThat(resp.fruits().getFirst().state()).as("flourishing: fruit state").isEqualTo("RIPE");
        assertThat(resp.fruits().getFirst().containerId()).as("flourishing: container id").isNotNull();
        assertThat(resp.commitSha()).as("flourishing: commit sha").isNotNull();
        return resp;
    }

    private int sshPortOf(UUID groveId) {
        GroveResponse grove = groves.get(groveId);
        return grove == null || grove.plot() == null || grove.plot().sshPort() == null ? 0 : grove.plot().sshPort();
    }

    private void assertSecretDelivered(UUID groveId, int sshPort, String secret, String step) throws Exception {
        FruitShell.of(groveService, providers, groveId).assertSecretDelivered(E2E_SECRET_NAME, secret, step);
        assertThat(runOnSeedling(sshPort, "bash -lc 'printf %s \"$ORCHARD_E2E_SECRET\"'"))
            .as("%s: seedling login shell sees the secret", step).isEqualTo(secret);
        assertThat(runOnSeedling(sshPort, "findmnt -no FSTYPE,OPTIONS /run/orchard/secrets"))
            .as("%s: seedling secrets mount", step)
            .startsWith("tmpfs")
            .matches("(?s).*[ ,]ro(,.*|$)");
    }

    private static void assertSeedlingDiskFree(int sshPort, String secret) throws Exception {
        assertThat(runOnSeedling(sshPort,
            "cat > /dev/shm/.e2e-pattern && echo PATTERN_OK"
                + " && sudo find / -xdev -type f -exec grep -lF -f /dev/shm/.e2e-pattern {} + ;"
                + " rm -f /dev/shm/.e2e-pattern; true",
            secret.getBytes(StandardCharsets.UTF_8)))
            .as("seedling disk: pattern staged and no file contains the secret value").isEqualTo("PATTERN_OK");
    }

    private void assertSshConfigAvailable(UUID groveId, int sshPort) {
        ResponseEntity<String> response = restTemplate.getForEntity(
            "/api/groves/" + groveId + "/ssh-config", String.class);

        assertThat(response.getStatusCode()).as("ssh-config: status").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).as("ssh-config: body")
            .contains("Host orchard-")
            .contains("Port " + sshPort)
            .contains("User cultivator");
    }

    private void assertGroveListed(UUID groveId) {
        ResponseEntity<List<GroveResponse>> response = restTemplate.exchange(
            "/api/groves", HttpMethod.GET, HttpEntity.EMPTY, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).as("list: status").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).as("list: body").isNotNull()
            .extracting(GroveResponse::id)
            .contains(groveId);
    }
}
