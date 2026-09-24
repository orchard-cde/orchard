package dev.orchard.vine;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SshVineTest {

    @Test
    void commands_returnsAnSshExecutorForThatSeedling() {
        CommandRunner runner = new SshVine("10.0.0.1", 2222, UUID.randomUUID()).commands();

        assertThat(runner).isInstanceOf(SshExecutor.class);
    }

    @Test
    void commands_returnsTheSameRunnerOnRepeatedCalls() {
        SshVine vine = new SshVine("10.0.0.1", 2222, UUID.randomUUID());

        assertThat(vine.commands()).isSameAs(vine.commands());
    }

    /** Guards the decoupling: a vine can be built from a bare endpoint with no domain type. */
    @Test
    void vineIsConstructibleFromHostPortAndIdAlone() {
        SshVine vine = new SshVine("10.0.0.5", 2222, UUID.randomUUID());
        assertThat(vine.commands()).isNotNull();
    }
}
