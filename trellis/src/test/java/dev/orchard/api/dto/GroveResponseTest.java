package dev.orchard.api.dto;

import dev.orchard.core.model.*;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GroveResponseTest {

    @Test
    void fromModel_mapsAllFields() {
        UUID groveId = UUID.randomUUID();
        UUID cultivatorId = UUID.randomUUID();
        VmPlot plot = vmPlot(groveId);

        Seed seed = Seed.devcontainer().name("test").image("ubuntu").build();
        Fruit fruit = Fruit.bud(groveId, plot.id(), seed).withState(FruitState.RIPE)
            .withContainerDetails("abc123", List.of(new Fruit.PortMapping(8080, 8080, "tcp")));

        Grove grove = new Grove(
            groveId, cultivatorId, "my-grove", "https://github.com/user/repo", "main",
            "abc1234", GroveState.FLOURISHING, SeedSpec.AUTO, plot, List.of(fruit),
            Instant.now(), Instant.now()
        );

        GroveResponse response = GroveResponse.fromModel(grove);

        assertThat(response.id()).isEqualTo(groveId);
        assertThat(response.name()).isEqualTo("my-grove");
        assertThat(response.repositoryUrl()).isEqualTo("https://github.com/user/repo");
        assertThat(response.branch()).isEqualTo("main");
        assertThat(response.commitSha()).isEqualTo("abc1234");
        assertThat(response.state()).isEqualTo(GroveState.FLOURISHING);
        assertThat(response.plot().id()).isEqualTo(plot.id());
        assertThat(response.fruits()).hasSize(1);
        assertThat(response.fruits().getFirst().state()).isEqualTo("RIPE");
        assertThat(response.fruits().getFirst().containerId()).isEqualTo("abc123");
    }

    @Test
    void fromModel_vmPlot() {
        Grove grove = grove().withPlot(vmPlot(UUID.randomUUID()));

        PlotInfo plot = GroveResponse.fromModel(grove).plot();

        assertThat(plot.kind()).isEqualTo(PlotKind.VM);
        assertThat(plot.state()).isEqualTo(PlotState.SAPLING);
        assertThat(plot.providerId()).isEqualTo("qemu");
        assertThat(plot.host()).isEqualTo("192.168.1.1");
        assertThat(plot.sshPort()).isEqualTo(2222);
        assertThat(plot.cpuCores()).isEqualTo(2);
        assertThat(plot.memoryMb()).isEqualTo(4096);
        assertThat(plot.diskGb()).isEqualTo(20);
        assertThat(plot.runtime()).isNull();
    }

    @Test
    void fromModel_containerPlot() {
        Grove grove = grove().withPlot(containerPlot(UUID.randomUUID()));

        PlotInfo plot = GroveResponse.fromModel(grove).plot();

        assertThat(plot.kind()).isEqualTo(PlotKind.CONTAINER);
        assertThat(plot.providerId()).isEqualTo("docker");
        assertThat(plot.runtime()).isEqualTo(ContainerRuntime.PODMAN);
        assertThat(plot.cpuCores()).isEqualTo(2);
        assertThat(plot.memoryMb()).isEqualTo(4096);
        assertThat(plot.host()).isNull();
        assertThat(plot.sshPort()).isNull();
        assertThat(plot.diskGb()).isNull();
    }

    @Test
    void fromModel_nullPlot_nullPlotInfo() {
        GroveResponse response = GroveResponse.fromModel(grove());

        assertThat(response.plot()).isNull();
    }

    @Test
    void json_hasPlotNotSeedling_andNoSecrets() {
        VmPlot plot = vmPlot(UUID.randomUUID()).withAuthorizedKeys(List.of("ssh-ed25519 AAAA secret"));

        String json = JsonMapper.builder().build()
            .writeValueAsString(GroveResponse.fromModel(grove().withPlot(plot)));

        assertThat(json).contains("\"plot\"");
        assertThat(json).doesNotContain("\"seedling\"", "authorizedKeys", "providerInstanceId", "inst-1");
    }

    @Test
    void fromModel_handlesEmptyFruits() {
        Grove grove = Grove.plant(UUID.randomUUID(), "test", "https://github.com/user/repo", "main");

        GroveResponse response = GroveResponse.fromModel(grove);

        assertThat(response.fruits()).isEmpty();
    }

    @Test
    void primaryFruit_returnsFirstFruit() {
        GroveResponse.FruitInfo fruit1 = new GroveResponse.FruitInfo(
            UUID.randomUUID(), "RIPE", "abc", "container-1", "web");
        GroveResponse.FruitInfo fruit2 = new GroveResponse.FruitInfo(
            UUID.randomUUID(), "RIPE", "def", "container-2", "db");

        GroveResponse response = new GroveResponse(
            UUID.randomUUID(), "test", "url", "main", null,
            GroveState.FLOURISHING, null, null, List.of(fruit1, fruit2),
            Instant.now(), Instant.now()
        );

        assertThat(response.primaryFruit()).isEqualTo(fruit1);
    }

    @Test
    void primaryFruit_returnsNullWhenEmpty() {
        GroveResponse response = new GroveResponse(
            UUID.randomUUID(), "test", "url", "main", null,
            GroveState.PREPARING, null, null, List.of(),
            Instant.now(), Instant.now()
        );

        assertThat(response.primaryFruit()).isNull();
    }

    @Test
    void primaryFruit_returnsNullWhenNull() {
        GroveResponse response = new GroveResponse(
            UUID.randomUUID(), "test", "url", "main", null,
            GroveState.PREPARING, null, null, null,
            Instant.now(), Instant.now()
        );

        assertThat(response.primaryFruit()).isNull();
    }

    private static Grove grove() {
        return Grove.plant(UUID.randomUUID(), "test", "https://github.com/user/repo", "main");
    }

    private static VmPlot vmPlot(UUID groveId) {
        return new VmPlot(UUID.randomUUID(), groveId, "qemu", "inst-1", PlotState.SAPLING,
            Instant.now(), Instant.now(), "192.168.1.1", 2222, List.of(), VmSpec.small());
    }

    private static ContainerPlot containerPlot(UUID groveId) {
        return new ContainerPlot(UUID.randomUUID(), groveId, "docker", "c-1", PlotState.SAPLING,
            Instant.now(), Instant.now(), Path.of("/srv/groves/g1"), ContainerRuntime.PODMAN,
            new ContainerSpec(2, 4096));
    }
}
