package dev.orchard.e2e;

import dev.orchard.api.dto.CreateGroveRequest;
import dev.orchard.api.dto.GroveResponse;
import dev.orchard.core.model.GroveState;
import dev.orchard.core.model.PlotState;
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
import static dev.orchard.e2e.E2ETestConstants.GROVE_DORMANT_TIMEOUT;
import static dev.orchard.e2e.E2ETestConstants.GROVE_FLOURISHING_TIMEOUT;
import static dev.orchard.e2e.E2ETestConstants.POLL_INTERVAL;
import static dev.orchard.e2e.E2ETestConstants.TEST_REPO_BRANCH;
import static dev.orchard.e2e.E2ETestConstants.TEST_REPO_URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

final class GroveClient {

    private final RestTemplate restTemplate = new RestTemplate();
    private final String baseUrl;

    GroveClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    UUID plant() {
        var request = new CreateGroveRequest(TEST_REPO_URL, TEST_REPO_BRANCH, null, "small", null);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<GroveResponse> response = restTemplate.exchange(
            baseUrl + "/api/groves", HttpMethod.POST, new HttpEntity<>(request, headers), GroveResponse.class);

        assertThat(response.getStatusCode()).as("plant: status").isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).as("plant: body").isNotNull();
        assertThat(response.getBody().id()).as("plant: grove id").isNotNull();
        assertThat(response.getBody().state()).as("plant: initial state")
            .isIn(GroveState.PREPARING, GroveState.PLANTING);
        return response.getBody().id();
    }

    GroveResponse get(UUID groveId) {
        try {
            return restTemplate.getForEntity(baseUrl + "/api/groves/" + groveId, GroveResponse.class).getBody();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return null;
            }
            throw e;
        }
    }

    GroveResponse awaitFlourishing(UUID groveId, String step) {
        await()
            .atMost(GROVE_FLOURISHING_TIMEOUT)
            .pollInterval(POLL_INTERVAL)
            .failFast(() -> assertThat(stateOf(get(groveId)))
                .as("%s: grove %s blighted while waiting for FLOURISHING", step, groveId)
                .isNotEqualTo(GroveState.BLIGHTED))
            .untilAsserted(() -> assertThat(stateOf(get(groveId)))
                .as("%s: waiting for FLOURISHING", step)
                .isEqualTo(GroveState.FLOURISHING));

        GroveResponse resp = get(groveId);
        assertThat(resp.plot()).as("%s: plot", step).isNotNull();
        assertThat(resp.plot().state()).as("%s: plot state", step).isEqualTo(PlotState.ROOTED);
        assertThat(resp.plot().sshPort()).as("%s: ssh port", step).isBetween(49200, 49299);
        return resp;
    }

    void stopAndAwaitDormant(UUID groveId) {
        ResponseEntity<GroveResponse> response = restTemplate.postForEntity(
            baseUrl + "/api/groves/" + groveId + "/actions/stop", null, GroveResponse.class);
        assertThat(response.getStatusCode()).as("stop: status").isEqualTo(HttpStatus.OK);

        await()
            .atMost(GROVE_DORMANT_TIMEOUT)
            .pollInterval(POLL_INTERVAL)
            .untilAsserted(() -> assertStopped(groveId, GroveState.DORMANT, "stop"));
    }

    void assertStopped(UUID groveId, GroveState expected, String step) {
        GroveResponse resp = get(groveId);
        assertThat(stateOf(resp)).as("%s: grove state", step).isEqualTo(expected);
        assertThat(resp.plot().state()).as("%s: plot state", step).isEqualTo(PlotState.DORMANT);
    }

    void start(UUID groveId) {
        ResponseEntity<GroveResponse> response = restTemplate.postForEntity(
            baseUrl + "/api/groves/" + groveId + "/actions/start", null, GroveResponse.class);
        assertThat(response.getStatusCode()).as("start: status").isEqualTo(HttpStatus.OK);
    }

    void clearAndAwaitCleared(UUID groveId) {
        ResponseEntity<Void> response = restTemplate.exchange(
            baseUrl + "/api/groves/" + groveId, HttpMethod.DELETE, null, Void.class);
        assertThat(response.getStatusCode()).as("clear: status").isEqualTo(HttpStatus.NO_CONTENT);

        // Await a terminal state rather than CLEARED alone: ORPHANED is terminal too (#228), and
        // waiting only for CLEARED would burn the full timeout instead of failing with this message.
        await()
            .atMost(GROVE_CLEARED_TIMEOUT)
            .pollInterval(POLL_INTERVAL)
            .untilAsserted(() -> assertThat(stateOf(get(groveId)))
                .as("Waiting for teardown to reach a terminal state")
                .isIn(GroveState.CLEARED, GroveState.ORPHANED));

        assertThat(get(groveId).state())
            .as("clear: teardown reached a terminal state but it was ORPHANED, not CLEARED")
            .isEqualTo(GroveState.CLEARED);
    }

    void clearQuietly(UUID groveId) {
        try {
            GroveResponse resp = get(groveId);
            if (resp == null || isTerminal(resp.state())) {
                return;
            }
            restTemplate.delete(baseUrl + "/api/groves/" + groveId);
            await()
                .atMost(GROVE_CLEARED_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .ignoreExceptions()
                .until(() -> {
                    GroveResponse r = get(groveId);
                    return r == null || isTerminal(r.state());
                });
        } catch (Exception e) {
            // Best effort; killStrayVmsAndScratch reaps any VM left behind
        }
    }

    static String runOnSeedling(int sshPort, String command) throws Exception {
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
                try (var paths = Files.walk(e2eDir)) {
                    paths.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                        });
                }
            }
        } catch (Exception e) {
            // Best effort
        }
    }

    private static GroveState stateOf(GroveResponse resp) {
        return resp == null ? null : resp.state();
    }

    private static boolean isTerminal(GroveState state) {
        return state == GroveState.CLEARED || state == GroveState.ORPHANED;
    }
}
