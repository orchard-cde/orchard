package dev.orchard.e2e;

import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Watches for a secret value escaping into process argv lists, API responses, or database rows.
 *
 * <p>The {@code ps} samplers are coarse: one sample every ~250 ms on the trellis host and roughly
 * every second on the seedling, so an exec shorter than that, such as the injection exec itself, can
 * fall between samples; with podman on macOS only the host-side CLI argv is visible. The argv safety
 * of the injection exec is proven by {@code SecretInjectorTest.inject_valueNeverInCommandString}.
 */
final class SecretLeakProbe implements AutoCloseable {

    private static final List<String> TABLES = List.of("groves", "fruits", "secrets");
    private static final long SAMPLE_INTERVAL_MILLIS = 200;
    private static final Duration JOIN_TIMEOUT = Duration.ofSeconds(30);

    private final String value;
    private final ConcurrentLinkedQueue<String> leaks = new ConcurrentLinkedQueue<>();
    private final Thread hostSampler;
    private final Thread seedlingSampler;
    private volatile boolean running = true;
    private volatile int hostSamples;
    private volatile int seedlingSamples;

    private SecretLeakProbe(String value, IntSupplier seedlingSshPort) {
        this.value = value;
        this.hostSampler = Thread.ofPlatform().daemon().name("e2e-ps-host").start(this::sampleHost);
        this.seedlingSampler = seedlingSshPort == null ? null
            : Thread.ofPlatform().daemon().name("e2e-ps-seedling").start(() -> sampleSeedling(seedlingSshPort));
    }

    /** Samples {@code ps -eo args} on the trellis host. */
    static SecretLeakProbe onHost(String value) {
        return new SecretLeakProbe(value, null);
    }

    /** Samples the trellis host and, once {@code sshPort} returns a port, the seedling VM too. */
    static SecretLeakProbe onHostAndSeedling(String value, IntSupplier sshPort) {
        return new SecretLeakProbe(value, sshPort);
    }

    @Override
    public void close() throws InterruptedException {
        running = false;
        assertThat(hostSampler.join(JOIN_TIMEOUT)).as("ps sampler: host thread stopped").isTrue();
        assertThat(hostSamples).as("ps sampler: host samples taken").isPositive();
        if (seedlingSampler != null) {
            assertThat(seedlingSampler.join(JOIN_TIMEOUT)).as("ps sampler: seedling thread stopped").isTrue();
            assertThat(seedlingSamples).as("ps sampler: seedling samples taken").isPositive();
        }
        assertThat(leaks).as("ps sampler: argv lines containing the secret value").isEmpty();
    }

    static void assertRowsFree(JdbcTemplate jdbc, String value, String step) {
        for (String table : TABLES) {
            for (Map<String, Object> row : jdbc.queryForList("select * from " + table)) {
                assertThat(render(row)).as("%s: %s row free of the secret value", step, table).doesNotContain(value);
            }
        }
    }

    static void assertApiFree(TestRestTemplate rest, UUID groveId, String value, String step) {
        for (String path : List.of("/api/groves/" + groveId, "/api/groves", "/api/secrets")) {
            String body = rest.getForObject(path, String.class);
            assertThat(body).as("%s: GET %s body", step, path).isNotBlank().doesNotContain(value);
        }
    }

    private void sampleHost() {
        while (running) {
            try {
                Process ps = new ProcessBuilder("ps", "-eo", "args").redirectErrorStream(true).start();
                String output = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (ps.waitFor() == 0) {
                    record(output);
                    hostSamples++;
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                // Counted as a missed sample
            } finally {
                pause();
            }
        }
    }

    private void sampleSeedling(IntSupplier sshPort) {
        while (running) {
            try {
                int port = sshPort.getAsInt();
                if (port > 0) {
                    record(GroveClient.runOnSeedling(port, "ps -eo args"));
                    seedlingSamples++;
                }
            } catch (Throwable e) {
                // The seedling is not reachable over SSH yet; counted as a missed sample
            } finally {
                pause();
            }
        }
    }

    private void pause() {
        try {
            Thread.sleep(SAMPLE_INTERVAL_MILLIS);
        } catch (InterruptedException e) {
            running = false;
            Thread.currentThread().interrupt();
        }
    }

    private void record(String output) {
        output.lines().filter(line -> line.contains(value)).forEach(leaks::add);
    }

    private static String render(Map<String, Object> row) {
        return row.entrySet().stream()
            .map(e -> e.getKey() + "=" + (e.getValue() instanceof byte[] b
                ? new String(b, StandardCharsets.ISO_8859_1) : String.valueOf(e.getValue())))
            .collect(Collectors.joining(", "));
    }
}
