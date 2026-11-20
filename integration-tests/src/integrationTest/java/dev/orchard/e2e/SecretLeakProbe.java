package dev.orchard.e2e;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Watches for a secret value escaping into process argv lists or database rows. */
final class SecretLeakProbe implements AutoCloseable {

    private static final List<String> TABLES = List.of("groves", "fruits", "secrets");
    private static final long SAMPLE_INTERVAL_MILLIS = 200;

    private final String value;
    private final ConcurrentLinkedQueue<String> leaks = new ConcurrentLinkedQueue<>();
    private final Thread hostSampler;
    private final Thread seedlingSampler;
    private volatile boolean running = true;
    private volatile int samples;

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
        hostSampler.join();
        if (seedlingSampler != null) {
            seedlingSampler.join();
        }
        assertThat(samples).as("ps sampler: took samples").isPositive();
        assertThat(leaks).as("ps sampler: argv lines containing the secret value").isEmpty();
    }

    static void assertRowsFree(JdbcTemplate jdbc, String value, String step) {
        for (String table : TABLES) {
            for (Map<String, Object> row : jdbc.queryForList("select * from " + table)) {
                assertThat(render(row)).as("%s: %s row free of the secret value", step, table).doesNotContain(value);
            }
        }
    }

    private void sampleHost() {
        while (running) {
            try {
                Process ps = new ProcessBuilder("ps", "-eo", "args").redirectErrorStream(true).start();
                record(new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                ps.waitFor();
                samples++;
                Thread.sleep(SAMPLE_INTERVAL_MILLIS);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    return;
                }
            }
        }
    }

    private void sampleSeedling(IntSupplier sshPort) {
        while (running) {
            try {
                int port = sshPort.getAsInt();
                if (port > 0) {
                    record(GroveClient.runOnSeedling(port, "ps -eo args"));
                }
                Thread.sleep(SAMPLE_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                // The seedling is not reachable over SSH yet
            }
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
