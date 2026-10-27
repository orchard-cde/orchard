package dev.orchard.core.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SeedlingSpecTest {

    @Test
    void from_copiesEveryVmSpecField() {
        VmSpec spec = new VmSpec(3, 5000, 77, "sentinel-type", "sentinel-serial");

        assertThat(SeedlingSpec.from(spec))
            .isEqualTo(new SeedlingSpec("sentinel-type", 3, 5000, 77, "sentinel-serial"));
    }

    @Test
    void from_vmPlot_keepsMachineTypeAndSerialOutput() {
        VmPlot plot = new VmPlot(UUID.randomUUID(), UUID.randomUUID(), "p", null, PlotState.GERMINATING,
            Instant.now(), null, null, 22, List.of(), new VmSpec(3, 5000, 77, "sentinel-type", "sentinel-serial"));

        assertThat(SeedlingSpec.from(plot))
            .isEqualTo(new SeedlingSpec("sentinel-type", 3, 5000, 77, "sentinel-serial"));
    }

    @Test
    void from_containerPlot_zeroDiskNullMachineType() {
        ContainerPlot plot = new ContainerPlot(UUID.randomUUID(), UUID.randomUUID(), "p", null,
            PlotState.GERMINATING, Instant.now(), null, Path.of("/tmp/ws"), ContainerRuntime.DOCKER,
            new ContainerSpec(3, 5000));

        assertThat(SeedlingSpec.from(plot)).isEqualTo(new SeedlingSpec(null, 3, 5000, 0, null));
    }
}
