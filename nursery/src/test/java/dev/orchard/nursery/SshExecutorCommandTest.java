package dev.orchard.nursery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link SshExecutor#buildSshCommand} wires the seedling's address/port into the shared
 * {@link SshCommandBuilder} and carries the liveness options through. Exhaustive argv coverage
 * lives in {@link SshCommandBuilderTest}; real SSH integration is covered by FruitGrowerIT.
 */
class SshExecutorCommandTest {

    @Test
    void wiresSeedlingTargetAndLivenessOptions() {
        List<String> argv = new SshExecutor(TestSeedlings.fake(2222)).buildSshCommand("echo hi");

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
