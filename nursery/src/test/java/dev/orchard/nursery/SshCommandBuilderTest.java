package dev.orchard.nursery;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the argv produced by {@link SshCommandBuilder} — in particular that each {@code -o} option
 * is a tight, correctly-paired token so the options can't silently break if the list is reordered.
 */
class SshCommandBuilderTest {

    /** Asserts {@code value} appears immediately preceded by a {@code -o} flag (a valid ssh pair). */
    private static void assertOptionPair(List<String> argv, String value) {
        int idx = argv.indexOf(value);
        assertThat(idx).as("argv contains %s", value).isGreaterThanOrEqualTo(1);
        assertThat(argv.get(idx - 1)).as("%s is preceded by -o", value).isEqualTo("-o");
    }

    @Test
    void emitsBaseConnectionAndLivenessOptions() {
        List<String> argv = new SshCommandBuilder().host("10.0.0.1").port(22).build();

        assertThat(argv).startsWith("ssh");
        assertOptionPair(argv, "StrictHostKeyChecking=no");
        assertOptionPair(argv, "UserKnownHostsFile=/dev/null");
        assertOptionPair(argv, "ConnectTimeout=10");
        assertOptionPair(argv, "ServerAliveInterval=15");
        assertOptionPair(argv, "ServerAliveCountMax=4");
        assertThat(argv).containsSubsequence("-p", "22");
        assertThat(argv).endsWith("cultivator@10.0.0.1");
    }

    @Test
    void honoursConnectTimeoutAndBatchMode() {
        List<String> argv = new SshCommandBuilder()
            .host("h").port(2222).connectTimeoutSeconds(5).batchMode(true)
            .remoteCommand("echo ready")
            .build();

        assertOptionPair(argv, "ConnectTimeout=5");
        assertOptionPair(argv, "BatchMode=yes");
        assertThat(argv).containsSubsequence("-p", "2222");
        assertThat(argv).endsWith("echo ready");
    }

    @Test
    void omitsBatchModeByDefault() {
        List<String> argv = new SshCommandBuilder().host("h").port(22).build();
        assertThat(argv).doesNotContain("BatchMode=yes");
    }

    @Test
    void skipsIdentityFlagWhenKeyMissing() {
        List<String> argv = new SshCommandBuilder()
            .host("h").port(22)
            .identityKey(Path.of("/definitely/does/not/exist/orchard_ed25519"))
            .build();
        assertThat(argv).doesNotContain("-i");
    }

    @Test
    void omitsRemoteCommandWhenAbsent() {
        List<String> argv = new SshCommandBuilder().host("10.0.0.1").port(22).build();
        assertThat(argv).endsWith("cultivator@10.0.0.1");
    }
}
