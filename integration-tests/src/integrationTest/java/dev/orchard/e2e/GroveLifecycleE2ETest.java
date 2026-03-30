package dev.orchard.e2e;

import dev.orchard.api.dto.CreateGroveRequest;
import dev.orchard.api.dto.GroveResponse;
import dev.orchard.core.model.GroveState;
import dev.orchard.trellis.OrchardApplication;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static dev.orchard.e2e.E2ETestConstants.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(
    classes = OrchardApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles({"devserver", "e2etest"})
@ExtendWith(QemuPrerequisiteExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GroveLifecycleE2ETest {

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    private UUID groveId;
    private GroveResponse latestResponse;

    @Test
    @Order(1)
    void serverIsHealthy() {
        ResponseEntity<Map> health = restTemplate.getForEntity("/api/health", Map.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).containsEntry("status", "healthy");

        ResponseEntity<Map> ready = restTemplate.getForEntity("/api/health/ready", Map.class);
        assertThat(ready.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ready.getBody()).containsEntry("status", "ready");
    }

    @Test
    @Order(2)
    void plantGrove_returnsCreated() {
        var request = new CreateGroveRequest(TEST_REPO_URL, TEST_REPO_BRANCH, null, "small");

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Cultivator-Id", TEST_CULTIVATOR_ID.toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<GroveResponse> response = restTemplate.exchange(
            "/api/groves",
            HttpMethod.POST,
            new HttpEntity<>(request, headers),
            GroveResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isNotNull();
        assertThat(response.getBody().state()).isIn(GroveState.PREPARING, GroveState.PLANTING);

        groveId = response.getBody().id();
    }

    @Test
    @Order(3)
    void groveReachesFlourishing() {
        assertThat(groveId).as("groveId must be set by plantGrove test").isNotNull();

        await()
            .atMost(GROVE_FLOURISHING_TIMEOUT)
            .pollInterval(POLL_INTERVAL)
            .untilAsserted(() -> {
                GroveResponse resp = getGrove(groveId);
                assertThat(resp).isNotNull();
                assertThat(resp.state())
                    .as("Grove became BLIGHTED — provisioning failed")
                    .isNotEqualTo(GroveState.BLIGHTED);
                assertThat(resp.state())
                    .as("Waiting for FLOURISHING, currently %s", resp.state())
                    .isEqualTo(GroveState.FLOURISHING);
            });

        latestResponse = getGrove(groveId);

        assertThat(latestResponse.seedling()).isNotNull();
        assertThat(latestResponse.seedling().state()).isEqualTo("SAPLING");
        assertThat(latestResponse.seedling().ipAddress()).isEqualTo("127.0.0.1");
        assertThat(latestResponse.seedling().sshPort()).isBetween(49200, 49299);
        assertThat(latestResponse.fruits()).isNotEmpty();
        assertThat(latestResponse.fruits().getFirst().state()).isEqualTo("RIPE");
        assertThat(latestResponse.fruits().getFirst().containerId()).isNotNull();
        assertThat(latestResponse.commitSha()).isNotNull();
    }

    @Test
    @Order(4)
    void sshConfigIsAvailable() {
        assertThat(groveId).as("groveId must be set").isNotNull();

        ResponseEntity<String> response = restTemplate.getForEntity(
            "/api/groves/" + groveId + "/ssh-config", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Host orchard-");
        assertThat(response.getBody()).contains("Port " + latestResponse.seedling().sshPort());
        assertThat(response.getBody()).contains("User cultivator");
    }

    @Test
    @Order(5)
    void canSshIntoSeedling() throws Exception {
        assertThat(latestResponse).as("latestResponse must be set").isNotNull();

        int sshPort = latestResponse.seedling().sshPort();
        Path privateKey = Path.of(System.getProperty("user.home"), ".ssh", "orchard_ed25519");

        ProcessBuilder pb = new ProcessBuilder(
            "ssh",
            "-o", "StrictHostKeyChecking=no",
            "-o", "UserKnownHostsFile=/dev/null",
            "-o", "ConnectTimeout=10",
            "-i", privateKey.toString(),
            "-p", String.valueOf(sshPort),
            "cultivator@127.0.0.1",
            "echo orchard-e2e-ok"
        );
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output;
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().reduce("", (a, b) -> a + "\n" + b).trim();
        }
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);

        assertThat(finished).as("SSH command should complete within 30 seconds").isTrue();
        assertThat(process.exitValue()).as("SSH exit code").isZero();
        assertThat(output).contains("orchard-e2e-ok");
    }

    @Test
    @Order(6)
    void canListGroves() {
        assertThat(groveId).as("groveId must be set").isNotNull();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Cultivator-Id", TEST_CULTIVATOR_ID.toString());

        ResponseEntity<List<GroveResponse>> response = restTemplate.exchange(
            "/api/groves",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody())
            .extracting(GroveResponse::id)
            .contains(groveId);
    }

    @Test
    @Order(7)
    void clearGrove_returnsNoContent() {
        assertThat(groveId).as("groveId must be set").isNotNull();

        ResponseEntity<Void> response = restTemplate.exchange(
            "/api/groves/" + groveId,
            HttpMethod.DELETE,
            null,
            Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @Order(8)
    void groveReachesCleared() {
        assertThat(groveId).as("groveId must be set").isNotNull();

        await()
            .atMost(GROVE_CLEARED_TIMEOUT)
            .pollInterval(POLL_INTERVAL)
            .untilAsserted(() -> {
                GroveResponse resp = getGrove(groveId);
                assertThat(resp).isNotNull();
                assertThat(resp.state())
                    .as("Waiting for CLEARED, currently %s", resp.state())
                    .isEqualTo(GroveState.CLEARED);
            });
    }

    @AfterAll
    void tearDown() {
        // Layer 1: API-level cleanup if grove was planted but not cleared
        if (groveId != null) {
            try {
                GroveResponse resp = getGrove(groveId);
                if (resp != null && resp.state() != GroveState.CLEARED) {
                    restTemplate.delete("/api/groves/" + groveId);
                    await()
                        .atMost(GROVE_CLEARED_TIMEOUT)
                        .pollInterval(POLL_INTERVAL)
                        .ignoreExceptions()
                        .until(() -> {
                            GroveResponse r = getGrove(groveId);
                            return r == null || r.state() == GroveState.CLEARED;
                        });
                }
            } catch (Exception e) {
                // Fall through to process cleanup
            }
        }

        // Layer 2: Kill any orphaned QEMU processes spawned for E2E tests
        try {
            new ProcessBuilder("pkill", "-f", "qemu-system.*orchard-e2e")
                .redirectErrorStream(true)
                .start()
                .waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Best effort
        }

        // Layer 3: Clean up E2E temp directory
        try {
            Path e2eDir = Path.of("/tmp/orchard-e2e");
            if (Files.exists(e2eDir)) {
                Files.walk(e2eDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                    });
            }
        } catch (Exception e) {
            // Best effort
        }
    }

    private GroveResponse getGrove(UUID id) {
        ResponseEntity<GroveResponse> response = restTemplate.getForEntity(
            "/api/groves/" + id, GroveResponse.class);
        if (response.getStatusCode() == HttpStatus.NOT_FOUND) {
            return null;
        }
        return response.getBody();
    }
}
