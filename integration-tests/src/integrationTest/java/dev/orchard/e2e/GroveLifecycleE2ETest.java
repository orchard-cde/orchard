package dev.orchard.e2e;

import dev.orchard.api.dto.CreateGroveRequest;
import dev.orchard.api.dto.GroveResponse;
import dev.orchard.core.model.GroveState;
import dev.orchard.core.model.PlotState;
import dev.orchard.trellis.OrchardApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.Comparator;
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
@AutoConfigureTestRestTemplate
@ActiveProfiles({"devserver", "e2etest"})
@ExtendWith(QemuPrerequisiteExtension.class)
class GroveLifecycleE2ETest {

    @Autowired
    private TestRestTemplate restTemplate;

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
        UUID groveId = plantGrove();
        try {
            GroveResponse flourishing = awaitFlourishing(groveId);
            int sshPort = flourishing.plot().sshPort();

            assertSshConfigAvailable(groveId, sshPort);
            assertThat(runOnSeedling(sshPort, "echo orchard-e2e-ok")).as("ssh: output").contains("orchard-e2e-ok");
            assertGroveListed(groveId);

            runOnSeedling(sshPort, "echo kept > /workspace/.orchard-e2e-marker");
            stopGroveAndAwaitDormant(groveId);
            startGrove(groveId);
            GroveResponse resumed = awaitFlourishing(groveId);
            assertThat(resumed.fruits().getFirst().containerId()).as("start: same container")
                .isEqualTo(flourishing.fruits().getFirst().containerId());
            assertThat(runOnSeedling(resumed.plot().sshPort(), "cat /workspace/.orchard-e2e-marker"))
                .as("start: workspace file survived stop/start").contains("kept");

            clearGroveAndAwaitCleared(groveId);
        } finally {
            clearQuietly(groveId);
        }
    }

    @AfterAll
    static void killStrayVmsAndScratch() {
        try {
            new ProcessBuilder("pkill", "-f", "qemu-system.*orchard-e2e")
                .redirectErrorStream(true)
                .start()
                .waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Best effort
        }

        try {
            Path e2eDir = Path.of("/tmp/orchard-e2e");
            if (Files.exists(e2eDir)) {
                Files.walk(e2eDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                    });
            }
        } catch (Exception e) {
            // Best effort
        }
    }

    private UUID plantGrove() {
        var request = new CreateGroveRequest(TEST_REPO_URL, TEST_REPO_BRANCH, null, "small", null);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<GroveResponse> response = restTemplate.exchange(
            "/api/groves", HttpMethod.POST, new HttpEntity<>(request, headers), GroveResponse.class);

        assertThat(response.getStatusCode()).as("plant: status").isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).as("plant: body").isNotNull();
        assertThat(response.getBody().id()).as("plant: grove id").isNotNull();
        assertThat(response.getBody().state()).as("plant: initial state")
            .isIn(GroveState.PREPARING, GroveState.PLANTING);
        return response.getBody().id();
    }

    private GroveResponse awaitFlourishing(UUID groveId) {
        await()
            .atMost(GROVE_FLOURISHING_TIMEOUT)
            .pollInterval(POLL_INTERVAL)
            .failFast(() -> assertNotBlighted(groveId, getGrove(groveId)))
            .untilAsserted(() -> {
                GroveResponse resp = getGrove(groveId);
                assertThat(resp).isNotNull();
                assertThat(resp.state())
                    .as("Waiting for FLOURISHING, currently %s", resp.state())
                    .isEqualTo(GroveState.FLOURISHING);
            });

        GroveResponse resp = getGrove(groveId);
        assertThat(resp.plot()).as("flourishing: plot").isNotNull();
        assertThat(resp.plot().state()).as("flourishing: plot state").isEqualTo(PlotState.ROOTED);
        assertThat(resp.plot().host()).as("flourishing: plot host").isEqualTo("127.0.0.1");
        assertThat(resp.plot().sshPort()).as("flourishing: ssh port").isBetween(49200, 49299);
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

    private void stopGroveAndAwaitDormant(UUID groveId) {
        ResponseEntity<GroveResponse> response = restTemplate.postForEntity(
            "/api/groves/" + groveId + "/actions/stop", null, GroveResponse.class);
        assertThat(response.getStatusCode()).as("stop: status").isEqualTo(HttpStatus.OK);

        await()
            .atMost(GROVE_DORMANT_TIMEOUT)
            .pollInterval(POLL_INTERVAL)
            .untilAsserted(() -> {
                GroveResponse resp = getGrove(groveId);
                assertThat(resp.state()).as("stop: grove state").isEqualTo(GroveState.DORMANT);
                assertThat(resp.plot().state()).as("stop: plot state").isEqualTo(PlotState.DORMANT);
            });
    }

    private void startGrove(UUID groveId) {
        ResponseEntity<GroveResponse> response = restTemplate.postForEntity(
            "/api/groves/" + groveId + "/actions/start", null, GroveResponse.class);
        assertThat(response.getStatusCode()).as("start: status").isEqualTo(HttpStatus.OK);
    }

    private String runOnSeedling(int sshPort, String command) throws Exception {
        Path privateKey = Path.of(System.getProperty("orchard.ssh.key-path",
            System.getProperty("user.home") + "/.ssh/orchard_ed25519"));

        ProcessBuilder pb = new ProcessBuilder(
            "ssh",
            "-o", "StrictHostKeyChecking=no",
            "-o", "UserKnownHostsFile=/dev/null",
            "-o", "ConnectTimeout=10",
            "-i", privateKey.toString(),
            "-p", String.valueOf(sshPort),
            "cultivator@127.0.0.1",
            command
        );
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output;
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().reduce("", (a, b) -> a + "\n" + b).trim();
        }
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);

        assertThat(finished).as("ssh %s: should complete within 30 seconds", command).isTrue();
        assertThat(process.exitValue()).as("ssh %s: exit code (output: %s)", command, output).isZero();
        return output;
    }

    private void assertGroveListed(UUID groveId) {
        ResponseEntity<List<GroveResponse>> response = restTemplate.exchange(
            "/api/groves", HttpMethod.GET, HttpEntity.EMPTY, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).as("list: status").isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).as("list: body").isNotNull()
            .extracting(GroveResponse::id)
            .contains(groveId);
    }

    private void clearGroveAndAwaitCleared(UUID groveId) {
        ResponseEntity<Void> response = restTemplate.exchange(
            "/api/groves/" + groveId, HttpMethod.DELETE, null, Void.class);
        assertThat(response.getStatusCode()).as("clear: status").isEqualTo(HttpStatus.NO_CONTENT);

        // Await a terminal state rather than CLEARED alone: ORPHANED is terminal too (#228), and
        // waiting only for CLEARED would burn the full timeout instead of failing with this message.
        await()
            .atMost(GROVE_CLEARED_TIMEOUT)
            .pollInterval(POLL_INTERVAL)
            .untilAsserted(() -> {
                GroveResponse resp = getGrove(groveId);
                assertThat(resp).isNotNull();
                assertThat(resp.state())
                    .as("Waiting for teardown to reach a terminal state, currently %s", resp.state())
                    .isIn(GroveState.CLEARED, GroveState.ORPHANED);
            });

        assertThat(getGrove(groveId).state())
            .as("clear: teardown reached a terminal state but it was ORPHANED, not CLEARED")
            .isEqualTo(GroveState.CLEARED);
    }

    private void clearQuietly(UUID groveId) {
        try {
            GroveResponse resp = getGrove(groveId);
            if (resp == null || isTerminal(resp.state())) {
                return;
            }
            restTemplate.delete("/api/groves/" + groveId);
            await()
                .atMost(GROVE_CLEARED_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .ignoreExceptions()
                .until(() -> {
                    GroveResponse r = getGrove(groveId);
                    return r == null || isTerminal(r.state());
                });
        } catch (Exception e) {
            // Best effort; @AfterAll kills any VM left behind
        }
    }

    private static boolean isTerminal(GroveState state) {
        return state == GroveState.CLEARED || state == GroveState.ORPHANED;
    }

    private static void assertNotBlighted(UUID groveId, GroveResponse resp) {
        assertThat(resp == null ? null : resp.state())
            .as("Grove %s blighted while waiting for FLOURISHING: %s", groveId, resp)
            .isNotEqualTo(GroveState.BLIGHTED);
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
