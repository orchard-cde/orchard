package dev.orchard.core.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainerPlotTest {

    private static ContainerPlot plot(Instant readyAt) {
        return new ContainerPlot(UUID.randomUUID(), UUID.randomUUID(), "docker-local", "c-1",
            PlotState.GERMINATING, Instant.now(), readyAt, Path.of("/tmp/ws"),
            ContainerRuntime.PODMAN, new ContainerSpec(2, 2048));
    }

    @Test
    void withState_sapling_setsReadyAt() {
        assertThat(plot(null).withState(PlotState.ROOTED).readyAt()).isNotNull();
    }

    @Test
    void withState_otherState_preservesEverythingElse() {
        ContainerPlot original = plot(null);

        assertThat(original.withState(PlotState.WITHERED))
            .usingRecursiveComparison().ignoringFields("state").isEqualTo(original);
    }

    @Test
    void constructor_nullProviderId_throws() {
        assertThatThrownBy(() -> new ContainerPlot(UUID.randomUUID(), UUID.randomUUID(), null, null,
            PlotState.GERMINATING, Instant.now(), null, Path.of("/tmp/ws"),
            ContainerRuntime.PODMAN, new ContainerSpec(2, 2048)))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("providerId");
    }
}
