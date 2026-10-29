package dev.orchard.harvest;

import dev.orchard.core.model.ContainerPlot;
import dev.orchard.core.model.ContainerRuntime;
import dev.orchard.core.model.ContainerSpec;
import dev.orchard.core.model.Plot;
import dev.orchard.core.model.PlotSizes;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.SeedlingSpec;
import dev.orchard.core.model.VmPlot;
import dev.orchard.core.model.VmSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlotSerializerTest {

    private final PlotSerializer serializer = new PlotSerializer();

    private static VmPlot vmPlot(List<String> keys) {
        return new VmPlot(UUID.randomUUID(), UUID.randomUUID(), "qemu-local", "i-1", PlotState.ROOTED,
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:01:00Z"),
            "10.0.0.5", 2222, keys, new VmSpec(4, 8192, 40, "medium", "file"));
    }

    @Test
    void roundTrip_vmPlot() {
        VmPlot plot = vmPlot(List.of());

        assertThat(serializer.deserialize(serializer.serialize(plot))).isEqualTo(plot);
    }

    @Test
    void roundTrip_germinatingVmPlot_keepsNulls() {
        VmPlot plot = VmPlot.germinate(UUID.randomUUID(), "qemu-local",
            SeedlingSpec.of("small", PlotSizes.DEFAULT.small(), null));

        assertThat(serializer.deserialize(serializer.serialize(plot))).isEqualTo(plot);
    }

    @Test
    void roundTrip_containerPlot() {
        ContainerPlot plot = new ContainerPlot(UUID.randomUUID(), UUID.randomUUID(), "docker-local", "c-1",
            PlotState.ROOTED, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:01:00Z"),
            Path.of("/srv/groves/g1"), ContainerRuntime.PODMAN, new ContainerSpec(2, 4096));

        Plot restored = serializer.deserialize(serializer.serialize(plot));

        assertThat(restored).isEqualTo(plot);
    }

    @Test
    void serialize_omitsAuthorizedKeys() {
        String json = serializer.serialize(vmPlot(List.of("ssh-ed25519 AAAA test")));

        assertThat(json).doesNotContain("authorizedKeys").doesNotContain("ssh-ed25519");
        assertThat(json).contains("@type").contains("\"vm\"");
    }

    @Test
    void deserialize_unknownType_throws() {
        String json = serializer.serialize(vmPlot(List.of())).replace("\"vm\"", "\"pod\"");

        assertThatThrownBy(() -> serializer.deserialize(json)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void deserialize_unknownRuntime_throws() {
        ContainerPlot plot = new ContainerPlot(UUID.randomUUID(), UUID.randomUUID(), "docker-local", "c-1",
            PlotState.ROOTED, Instant.now(), null, Path.of("/srv/g"), ContainerRuntime.DOCKER,
            new ContainerSpec(2, 4096));
        String json = serializer.serialize(plot).replace("\"DOCKER\"", "\"LXC\"");

        assertThat(json).contains("LXC");
        assertThatThrownBy(() -> serializer.deserialize(json)).isInstanceOf(RuntimeException.class);
    }
}
