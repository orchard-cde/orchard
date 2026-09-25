package dev.orchard.vine;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SshVineTest {

    /**
     * Also guards the decoupling: the constructor call itself is the assertion that a vine can be
     * built from a bare endpoint with no domain type. If this ever needs a {@code Seedling} again,
     * {@code :vine} has re-acquired a real dependency on the domain model (#86 stage 2).
     */
    @Test
    void commands_returnsTheSshRunnerForThatEndpoint() {
        CommandRunner runner = new SshVine("10.0.0.1", 2222, UUID.randomUUID()).commands();

        assertThat(runner).isInstanceOf(SshExecutor.class);
    }
}
