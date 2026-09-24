package dev.orchard.vine;

import dev.orchard.core.model.Seedling;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SshVineTest {

    @Test
    void commands_returnsAnSshExecutorForThatSeedling() {
        Seedling s = VineTestSeedlings.fake(2222);

        CommandRunner runner = new SshVine(s.ipAddress(), s.sshPort(), s.id()).commands();

        assertThat(runner).isInstanceOf(SshExecutor.class);
    }

    @Test
    void commands_returnsTheSameRunnerOnRepeatedCalls() {
        Seedling s = VineTestSeedlings.fake(2222);
        SshVine vine = new SshVine(s.ipAddress(), s.sshPort(), s.id());

        assertThat(vine.commands()).isSameAs(vine.commands());
    }

    /**
     * Guards the decoupling: a vine can be built from a bare endpoint with no domain type. If this
     * ever needs a Seedling again, :vine has re-acquired a :core dependency.
     */
    @Test
    void vineIsConstructibleFromHostPortAndIdAlone() {
        SshVine vine = new SshVine("10.0.0.5", 2222, UUID.randomUUID());
        assertThat(vine.commands()).isNotNull();
    }
}
