package dev.orchard.vine;

import dev.orchard.core.model.Seedling;
import dev.orchard.core.model.SeedlingState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link SshExecutor#buildSshCommand} wires the seedling's address/port into the shared
 * {@link SshCommandBuilder} and carries the liveness options through. Exhaustive argv coverage
 * lives in {@link SshCommandBuilderTest}; real SSH integration is covered by FruitGrowerIT.
 */
class SshExecutorCommandTest {

    /** Mirrors {@code TestSeedlings.fake(sshPort)} from nursery's test sources — not reachable
     * from :vine's test sources, since :nursery depends on :vine and not the reverse. */
    private static Seedling fakeSeedling(int sshPort) {
        return new Seedling(
            UUID.randomUUID(), UUID.randomUUID(), "i-fake", "10.0.0.1", sshPort,
            SeedlingState.SAPLING, Seedling.SeedlingSpec.small(), Instant.now(), Instant.now());
    }

    @Test
    void wiresSeedlingTargetAndLivenessOptions() {
        List<String> argv = new SshExecutor(fakeSeedling(2222)).buildSshCommand("echo hi");

        assertThat(argv).startsWith("ssh");
        // The liveness option must be a tight `-o ServerAliveInterval=15` pair (issue #138).
        int idx = argv.indexOf("ServerAliveInterval=15");
        assertThat(idx).isGreaterThanOrEqualTo(1);
        assertThat(argv.get(idx - 1)).isEqualTo("-o");

        assertThat(argv).containsSubsequence("-p", "2222");
        assertThat(argv).containsSubsequence("-o", "ServerAliveCountMax=4");
        assertThat(argv).contains("cultivator@10.0.0.1");
        assertThat(argv).endsWith("echo hi");
    }
}
