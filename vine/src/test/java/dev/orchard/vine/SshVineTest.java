package dev.orchard.vine;

import dev.orchard.core.model.Seedling;
import dev.orchard.core.model.SeedlingState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SshVineTest {

    private static Seedling seedling() {
        return new Seedling(
            UUID.randomUUID(), UUID.randomUUID(), "i-fake", "10.0.0.1", 2222,
            SeedlingState.SAPLING, Seedling.SeedlingSpec.small(), Instant.now(), Instant.now());
    }

    @Test
    void commands_returnsAnSshExecutorForThatSeedling() {
        Seedling s = seedling();

        CommandRunner runner = new SshVine(s).commands();

        assertThat(runner).isInstanceOf(SshExecutor.class);
    }

    @Test
    void commands_returnsTheSameRunnerOnRepeatedCalls() {
        SshVine vine = new SshVine(seedling());

        assertThat(vine.commands()).isSameAs(vine.commands());
    }
}
