package dev.orchard.e2e;

import dev.orchard.api.dto.CreateGroveRequest;
import dev.orchard.api.dto.GroveResponse;
import dev.orchard.core.model.GroveState;
import dev.orchard.trellis.OrchardApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static dev.orchard.e2e.E2ETestConstants.GROVE_CLEARED_TIMEOUT;
import static dev.orchard.e2e.E2ETestConstants.GROVE_FLOURISHING_TIMEOUT;
import static dev.orchard.e2e.E2ETestConstants.POLL_INTERVAL;
import static dev.orchard.e2e.E2ETestConstants.TEST_REPO_BRANCH;
import static dev.orchard.e2e.E2ETestConstants.TEST_REPO_URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@ExtendWith(QemuPrerequisiteExtension.class)
class GroveSurvivesRestartE2ETest {

    private static final Logger log = LoggerFactory.getLogger(GroveSurvivesRestartE2ETest.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final String datasourceUrl =
        "jdbc:h2:mem:orchard-e2e-restart-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    private ConfigurableApplicationContext context;
    private String baseUrl;

    @BeforeEach
    void startServer() {
        startTrellis();
    }

    @AfterEach
    void stopServerAndStrayVms() {
        try {
            new ProcessBuilder("pkill", "-f", "qemu-system.*orchard-e2e")
                .redirectErrorStream(true)
                .start()
                .waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Best effort
        }

        if (context != null && context.isActive()) {
            context.close();
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

    @Test
    void flourishingGroveSurvivesServerRestartAndUprootsCleanly() throws Exception {
        UUID groveId = plantGrove();
        try {
            GroveResponse beforeRestart = awaitFlourishing(groveId, "before restart");
            assertThat(beforeRestart.plot()).as("before restart: plot").isNotNull();
            assertThat(beforeRestart.plot().sshPort()).as("before restart: ssh port").isBetween(49200, 49299);
            verifySshConnection(beforeRestart.plot().sshPort(), "before restart");

            restartTrellis();

            GroveResponse afterRestart = awaitFlourishing(groveId, "after restart");
            verifySshConnection(afterRestart.plot().sshPort(), "after restart");

            uprootAndAssertNoOrphans(groveId, afterRestart.plot().id());
        } finally {
            clearQuietly(groveId);
        }
    }

    private void startTrellis() {
        context = SpringApplication.run(OrchardApplication.class,
            "--spring.profiles.active=devserver,e2etest",
            "--spring.datasource.url=" + datasourceUrl,
            "--server.port=0");
        baseUrl = "http://localhost:" + context.getEnvironment().getProperty("local.server.port");
        log.info("Server started at {}", baseUrl);
    }

    private void restartTrellis() {
        log.info("Shutting down server for simulated restart...");
        context.close();
        startTrellis();
    }

    private UUID plantGrove() {
        var request = new CreateGroveRequest(TEST_REPO_URL, TEST_REPO_BRANCH, null, "small", null);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<GroveResponse> response = restTemplate.exchange(
            baseUrl + "/api/groves", HttpMethod.POST, new HttpEntity<>(request, headers), GroveResponse.class);

        assertThat(response.getStatusCode()).as("plant: status").isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).as("plant: body").isNotNull();
        return response.getBody().id();
    }

    private GroveResponse awaitFlourishing(UUID groveId, String step) {
        await()
            .atMost(GROVE_FLOURISHING_TIMEOUT)
            .pollInterval(POLL_INTERVAL)
            .failFast(() -> assertNotBlighted(groveId, getGrove(groveId)))
            .untilAsserted(() -> {
                GroveResponse resp = getGrove(groveId);
                assertThat(resp).as("%s: grove must be found", step).isNotNull();
                assertThat(resp.state())
                    .as("%s: waiting for FLOURISHING, currently %s", step, resp.state())
                    .isEqualTo(GroveState.FLOURISHING);
            });

        GroveResponse resp = getGrove(groveId);
        assertThat(resp).as("%s: grove", step).isNotNull();
        return resp;
    }

    private void uprootAndAssertNoOrphans(UUID groveId, UUID plotId) throws Exception {
        ResponseEntity<Void> response = restTemplate.exchange(
            baseUrl + "/api/groves/" + groveId, HttpMethod.DELETE, null, Void.class);
        assertThat(response.getStatusCode()).as("uproot: status").isEqualTo(HttpStatus.NO_CONTENT);

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
            .as("uproot: teardown reached a terminal state but it was ORPHANED, not CLEARED")
            .isEqualTo(GroveState.CLEARED);

        Process pgrep = new ProcessBuilder("pgrep", "-f", "orchard-" + plotId.toString().substring(0, 8))
            .redirectErrorStream(true)
            .start();
        boolean finished = pgrep.waitFor(10, TimeUnit.SECONDS);
        assertThat(finished).as("uproot: pgrep should complete within 10 seconds").isTrue();
        assertThat(pgrep.exitValue())
            .as("uproot: no orphaned QEMU process should remain for plot %s", plotId)
            .isNotZero();
    }

    private void clearQuietly(UUID groveId) {
        try {
            GroveResponse resp = getGrove(groveId);
            if (resp == null || isTerminal(resp.state())) {
                return;
            }
            restTemplate.delete(baseUrl + "/api/groves/" + groveId);
            await()
                .atMost(GROVE_CLEARED_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .ignoreExceptions()
                .until(() -> {
                    GroveResponse r = getGrove(groveId);
                    return r == null || isTerminal(r.state());
                });
        } catch (Exception e) {
            // Best effort; @AfterEach kills any VM left behind
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
        try {
            ResponseEntity<GroveResponse> response = restTemplate.getForEntity(
                baseUrl + "/api/groves/" + id, GroveResponse.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return null;
            }
            throw e;
        }
    }

    private void verifySshConnection(int sshPort, String step) throws Exception {
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
            "echo orchard-e2e-restart-ok"
        );
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output;
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().reduce("", (a, b) -> a + "\n" + b).trim();
        }
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);

        assertThat(finished).as("%s: SSH command should complete within 30 seconds", step).isTrue();
        assertThat(process.exitValue()).as("%s: SSH exit code", step).isZero();
        assertThat(output).as("%s: SSH output should contain success marker", step).contains("orchard-e2e-restart-ok");
    }
}
