package dev.orchard.api.service;

import dev.orchard.core.model.ContainerPlot;
import dev.orchard.core.model.ContainerRuntime;
import dev.orchard.core.model.ContainerSpec;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.Seedling;
import dev.orchard.core.model.SeedlingState;
import dev.orchard.core.model.VmPlot;
import dev.orchard.core.model.VmSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlotBridgeTest {

    private static Seedling fullySpecified(SeedlingState state) {
        return new Seedling(UUID.randomUUID(), UUID.randomUUID(), "i-77", "10.9.8.7", 2201,
            state, new VmSpec(3, 5000, 77, "sentinel-type", "sentinel-serial"),
            Instant.parse("2026-01-02T03:04:05.123456Z"), Instant.parse("2026-01-02T03:05:00Z"),
            List.of("ssh-ed25519 AAAA key-one", "ssh-ed25519 BBBB key-two"));
    }

    @Test
    void toPlot_copiesEveryFieldAndStampsProvider() {
        Seedling s = fullySpecified(SeedlingState.SAPLING);

        VmPlot plot = PlotBridge.toPlot(s, "qemu-local");

        assertThat(plot.id()).isEqualTo(s.id());
        assertThat(plot.groveId()).isEqualTo(s.groveId());
        assertThat(plot.providerId()).isEqualTo("qemu-local");
        assertThat(plot.providerInstanceId()).isEqualTo("i-77");
        assertThat(plot.state()).isEqualTo(PlotState.SAPLING);
        assertThat(plot.plantedAt()).isEqualTo(s.plantedAt());
        assertThat(plot.readyAt()).isEqualTo(s.readyAt());
        assertThat(plot.host()).isEqualTo("10.9.8.7");
        assertThat(plot.sshPort()).isEqualTo(2201);
        assertThat(plot.authorizedKeys()).containsExactly("ssh-ed25519 AAAA key-one", "ssh-ed25519 BBBB key-two");
        assertThat(plot.spec()).isEqualTo(s.spec());
    }

    @Test
    void roundTrip_isLossless() {
        Seedling s = fullySpecified(SeedlingState.WILTING);

        assertThat(PlotBridge.toSeedling(PlotBridge.toPlot(s, "aws-ec2"))).isEqualTo(s);
    }

    @Test
    void nullStateSurvivesBothDirections() {
        Seedling s = fullySpecified(null);

        VmPlot plot = PlotBridge.toPlot(s, "p");

        assertThat(plot.state()).isNull();
        assertThat(PlotBridge.toSeedling(plot).state()).isNull();
    }

    @Test
    void toSeedling_containerPlot_isRefused() {
        ContainerPlot container = new ContainerPlot(UUID.randomUUID(), UUID.randomUUID(), "docker-local",
            "c-1", PlotState.SAPLING, Instant.now(), null, Path.of("/tmp/ws"),
            ContainerRuntime.DOCKER, new ContainerSpec(2, 2048));

        assertThatThrownBy(() -> PlotBridge.toSeedling(container))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(container.id().toString());
    }

    @Test
    void plotStateAndSeedlingStateHaveIdenticalConstants() {
        assertThat(Arrays.stream(PlotState.values()).map(Enum::name))
            .containsExactlyInAnyOrderElementsOf(
                Arrays.stream(SeedlingState.values()).map(Enum::name).toList());
    }
}
