package dev.orchard.e2e;

import dev.orchard.api.dto.GroveResponse;
import dev.orchard.trellis.OrchardApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.orchard.e2e.GroveClient.runOnSeedling;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = OrchardApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
@ActiveProfiles({"devserver", "e2etest"})
@ExtendWith(QemuPrerequisiteExtension.class)
class GroveLifecycleE2ETest {

    @Autowired
    private TestRestTemplate restTemplate;

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
    void plantedGroveFlourishesServesSshAndClears() throws Exception {
        UUID groveId = groves.plant();
        try {
            GroveResponse flourishing = awaitFlourishing(groveId);
            int sshPort = flourishing.plot().sshPort();

            assertSshConfigAvailable(groveId, sshPort);
            assertThat(runOnSeedling(sshPort, "echo orchard-e2e-ok")).as("ssh: output").contains("orchard-e2e-ok");
            assertGroveListed(groveId);

            runOnSeedling(sshPort, "echo kept > /workspace/.orchard-e2e-marker");
            groves.stopAndAwaitDormant(groveId);
            groves.start(groveId);
            GroveResponse resumed = awaitFlourishing(groveId);
            assertThat(resumed.fruits().getFirst().containerId()).as("start: same container")
                .isEqualTo(flourishing.fruits().getFirst().containerId());
            assertThat(runOnSeedling(resumed.plot().sshPort(), "cat /workspace/.orchard-e2e-marker"))
                .as("start: workspace file survived stop/start").contains("kept");

            groves.clearAndAwaitCleared(groveId);
        } finally {
            groves.clearQuietly(groveId);
        }
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
