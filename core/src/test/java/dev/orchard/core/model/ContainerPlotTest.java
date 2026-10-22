package dev.orchard.core.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerPlotTest {

    private static ContainerPlot plot(Instant readyAt) {
        return new ContainerPlot(UUID.randomUUID(), UUID.randomUUID(), "docker-local", "c-1",
            PlotState.GERMINATING, Instant.now(), readyAt, Path.of("/tmp/ws"),
            ContainerRuntime.PODMAN, new ContainerSpec(2, 2048));
    }

    @Test
    void withState_sapling_setsReadyAt() {
        assertThat(plot(null).withState(PlotState.SAPLING).readyAt()).isNotNull();
    }

    @Test
    void withState_otherState_preservesEverythingElse() {
        ContainerPlot original = plot(null);

        assertThat(original.withState(PlotState.WITHERED))
            .usingRecursiveComparison().ignoringFields("state").isEqualTo(original);
    }
}
