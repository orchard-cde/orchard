package dev.orchard.vine;

import dev.orchard.core.model.Seedling;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SshVineTest {

    @Test
    void commands_returnsAnSshExecutorForThatSeedling() {
        Seedling s = VineTestSeedlings.fake(2222);

        CommandRunner runner = new SshVine(s).commands();

        assertThat(runner).isInstanceOf(SshExecutor.class);
    }

    @Test
    void commands_returnsTheSameRunnerOnRepeatedCalls() {
        SshVine vine = new SshVine(VineTestSeedlings.fake(2222));

        assertThat(vine.commands()).isSameAs(vine.commands());
    }
}
