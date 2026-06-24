package dev.orchard.e2e;

import dev.orchard.api.dto.CreateGroveRequest;
import dev.orchard.api.dto.GroveResponse;
import dev.orchard.core.model.GroveState;
import dev.orchard.trellis.OrchardApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
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
import static dev.orchard.e2e.E2ETestConstants.TEST_CULTIVATOR_ID;
import static dev.orchard.e2e.E2ETestConstants.TEST_REPO_BRANCH;
import static dev.orchard.e2e.E2ETestConstants.TEST_REPO_URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@ExtendWith(QemuPrerequisiteExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GroveSurvivesRestartE2ETest {

    private static final Logger log = LoggerFactory.getLogger(GroveSurvivesRestartE2ETest.class);

    private ConfigurableApplicationContext context;
    private final RestTemplate restTemplate = new RestTemplate();
    private int port;
    private String baseUrl;
    private UUID groveId;
    private GroveResponse latestResponse;

    @BeforeAll
    void startServer() {
        context = SpringApplication.run(OrchardApplication.class,
            "--spring.profiles.active=devserver,e2etest",
            "--server.port=0");
        port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        baseUrl = "http://localhost:" + port;
        log.info("Server started on port {}", port);
    }

    @Test
    @Order(1)
    void plantGroveAndWaitForFlourishing() throws Exception {
        var request = new CreateGroveRequest(TEST_REPO_URL, TEST_REPO_BRANCH, null, "small", null);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Cultivator-Id", TEST_CULTIVATOR_ID.toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<GroveResponse> response = restTemplate.exchange(
            baseUrl + "/api/groves",
            HttpMethod.POST,
            new HttpEntity<>(request, headers),
            GroveResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        groveId = response.getBody().id();

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
        assertThat(latestResponse).isNotNull();
        assertThat(latestResponse.seedling()).isNotNull();
        assertThat(latestResponse.seedling().sshPort()).isBetween(49200, 49299);

        verifySshConnection(latestResponse.seedling().sshPort());
    }

    @Test
    @Order(2)
    void restartServer() {
        assertThat(groveId).as("groveId must be set by previous test").isNotNull();
        log.info("Shutting down server for simulated restart...");

        context.close();
        log.info("Server shut down. Starting new server instance...");

        context = SpringApplication.run(OrchardApplication.class,
            "--spring.profiles.active=devserver,e2etest",
            "--server.port=0");
        port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        baseUrl = "http://localhost:" + port;
        log.info("Server restarted on port {}", port);
    }

    @Test
    @Order(3)
    void groveStillFlourishingAfterRestart() throws Exception {
        assertThat(groveId).as("groveId must be set by previous test").isNotNull();
        assertThat(latestResponse).as("latestResponse must be set by previous test").isNotNull();

        await()
            .atMost(GROVE_FLOURISHING_TIMEOUT)
            .pollInterval(POLL_INTERVAL)
            .untilAsserted(() -> {
                GroveResponse resp = getGrove(groveId);
                assertThat(resp).as("Grove must be found after restart").isNotNull();
                assertThat(resp.state())
                    .as("Grove should be FLOURISHING after restart, not %s", resp.state())
                    .isNotEqualTo(GroveState.BLIGHTED);
                assertThat(resp.state())
                    .as("Grove must still be FLOURISHING after restart, currently %s", resp.state())
                    .isEqualTo(GroveState.FLOURISHING);
            });

        latestResponse = getGrove(groveId);
        assertThat(latestResponse).isNotNull();
        verifySshConnection(latestResponse.seedling().sshPort());
    }

    @Test
    @Order(4)
    void uprootGroveAndVerifyNoOrphans() throws Exception {
        assertThat(groveId).as("groveId must be set").isNotNull();

        ResponseEntity<Void> response = restTemplate.exchange(
            baseUrl + "/api/groves/" + groveId,
            HttpMethod.DELETE,
            null,
            Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

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

        UUID seedlingId = latestResponse.seedling().id();
        Process pgrep = new ProcessBuilder("pgrep", "-f", "orchard-" + seedlingId.toString().substring(0, 8))
            .redirectErrorStream(true)
            .start();
        boolean finished = pgrep.waitFor(10, TimeUnit.SECONDS);
        assertThat(finished).as("pgrep should complete within 10 seconds").isTrue();
        assertThat(pgrep.exitValue())
            .as("No orphaned QEMU process should remain for seedling %s", seedlingId)
            .isNotZero();
    }

    @AfterAll
    void tearDown() {
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

    private void verifySshConnection(int sshPort) throws Exception {
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

        assertThat(finished).as("SSH command should complete within 30 seconds").isTrue();
        assertThat(process.exitValue()).as("SSH exit code").isZero();
        assertThat(output).as("SSH output should contain success marker").contains("orchard-e2e-restart-ok");
    }
}
