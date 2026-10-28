package dev.orchard.core.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GroveTest {

    private final UUID cultivatorId = UUID.randomUUID();

    @Test
    void plant_createsGroveInPreparingState() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");

        assertThat(grove.state()).isEqualTo(GroveState.PREPARING);
    }

    @Test
    void plant_generatesUniqueId() {
        Grove grove1 = Grove.plant(cultivatorId, "grove-1", "https://github.com/test/repo", "main");
        Grove grove2 = Grove.plant(cultivatorId, "grove-2", "https://github.com/test/repo", "main");

        assertThat(grove1.id()).isNotEqualTo(grove2.id());
    }

    @Test
    void plant_setsTimestamps() {
        Instant before = Instant.now();
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");
        Instant after = Instant.now();

        assertThat(grove.plantedAt()).isBetween(before, after);
        assertThat(grove.lastAccessedAt()).isBetween(before, after);
    }

    @Test
    void plant_setsNullPlotAndEmptyFruits() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");

        assertThat(grove.plot()).isNull();
        assertThat(grove.fruits()).isEmpty();
        assertThat(grove.commitSha()).isNull();
    }

    @Test
    void withState_transitionsState() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");

        Grove updated = grove.withState(GroveState.PLANTING);

        assertThat(updated.state()).isEqualTo(GroveState.PLANTING);
    }

    @Test
    void withState_updatesLastAccessedAt() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");
        Instant originalTime = grove.lastAccessedAt();

        Grove updated = grove.withState(GroveState.PLANTING);

        assertThat(updated.lastAccessedAt()).isAfterOrEqualTo(originalTime);
    }

    @Test
    void withState_preservesOtherFields() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");

        Grove updated = grove.withState(GroveState.PLANTING);

        assertThat(updated.id()).isEqualTo(grove.id());
        assertThat(updated.cultivatorId()).isEqualTo(cultivatorId);
        assertThat(updated.name()).isEqualTo("my-grove");
        assertThat(updated.repositoryUrl()).isEqualTo("https://github.com/test/repo");
        assertThat(updated.branch()).isEqualTo("main");
    }

    @Test
    void withPlot_attachesPlot() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");
        VmPlot plot = createReadyPlot(grove.id());

        Grove updated = grove.withPlot(plot);

        assertThat(updated.plot()).isEqualTo(plot);
    }

    @Test
    void withFruit_addsNewFruit() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");
        Fruit fruit = createRipeFruit(grove.id(), UUID.randomUUID());

        Grove updated = grove.withFruit(fruit);

        assertThat(updated.fruits()).hasSize(1);
        assertThat(updated.fruits().getFirst().id()).isEqualTo(fruit.id());
    }

    @Test
    void withFruit_replacesExistingFruitById() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");
        Fruit fruit = Fruit.bud(grove.id(), UUID.randomUUID(),
                Seed.devcontainer().name("test").image("ubuntu").build());
        grove = grove.withFruit(fruit);

        Fruit ripeFruit = fruit.withState(FruitState.RIPE);
        Grove updated = grove.withFruit(ripeFruit);

        assertThat(updated.fruits()).hasSize(1);
        assertThat(updated.fruits().getFirst().state()).isEqualTo(FruitState.RIPE);
    }

    @Test
    void withFruits_replacesEntireList() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");
        Fruit fruit1 = createRipeFruit(grove.id(), UUID.randomUUID());
        Fruit fruit2 = createRipeFruit(grove.id(), UUID.randomUUID());

        Grove updated = grove.withFruits(List.of(fruit1, fruit2));

        assertThat(updated.fruits()).hasSize(2);
    }

    @Test
    void withCommit_setsCommitSha() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");

        Grove updated = grove.withCommit("abc123def456");

        assertThat(updated.commitSha()).isEqualTo("abc123def456");
    }

    @Test
    void primaryFruit_returnsFirstFruit() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");
        Fruit fruit1 = createRipeFruit(grove.id(), UUID.randomUUID());
        Fruit fruit2 = createRipeFruit(grove.id(), UUID.randomUUID());
        grove = grove.withFruits(List.of(fruit1, fruit2));

        assertThat(grove.primaryFruit()).isEqualTo(fruit1);
    }

    @Test
    void primaryFruit_returnsNullWhenEmpty() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");

        assertThat(grove.primaryFruit()).isNull();
    }

    @Test
    void primaryFruit_returnsNullWhenNull() {
        Grove grove = new Grove(UUID.randomUUID(), cultivatorId, "my-grove",
                "https://github.com/test/repo", "main", null, GroveState.PREPARING,
                SeedSpec.AUTO, null, null, Instant.now(), Instant.now());

        assertThat(grove.primaryFruit()).isNull();
    }

    @Test
    void isReady_trueWhenFullyReady() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");
        VmPlot plot = createReadyPlot(grove.id());
        Fruit fruit = createRipeFruit(grove.id(), plot.id());

        grove = grove.withState(GroveState.FLOURISHING)
                .withPlot(plot)
                .withFruit(fruit);

        assertThat(grove.isReady()).isTrue();
    }

    @Test
    void isReady_falseWhenNotFlourishing() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");
        VmPlot plot = createReadyPlot(grove.id());
        Fruit fruit = createRipeFruit(grove.id(), plot.id());

        grove = grove.withState(GroveState.GROWING)
                .withPlot(plot)
                .withFruit(fruit);

        assertThat(grove.isReady()).isFalse();
    }

    @Test
    void isReady_falseWhenPlotNull() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main")
                .withState(GroveState.FLOURISHING)
                .withFruit(createRipeFruit(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(grove.isReady()).isFalse();
    }

    @Test
    void isReady_falseWhenPlotNotRooted() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");
        VmPlot plot = VmPlot.germinate(grove.id(), "qemu", SeedlingSpec.from(VmSpec.small()));

        grove = grove.withState(GroveState.FLOURISHING)
                .withPlot(plot)
                .withFruit(createRipeFruit(grove.id(), plot.id()));

        assertThat(grove.isReady()).isFalse();
    }

    @Test
    void isReady_falseWhenFruitsEmpty() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");
        VmPlot plot = createReadyPlot(grove.id());

        grove = grove.withState(GroveState.FLOURISHING).withPlot(plot);

        assertThat(grove.isReady()).isFalse();
    }

    @Test
    void isReady_falseWhenAnyFruitNotReady() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");
        VmPlot plot = createReadyPlot(grove.id());
        Fruit ripeFruit = createRipeFruit(grove.id(), plot.id());
        Fruit buddingFruit = Fruit.bud(grove.id(), plot.id(),
                Seed.devcontainer().name("unripe").image("ubuntu").build());

        grove = grove.withState(GroveState.FLOURISHING)
                .withPlot(plot)
                .withFruits(List.of(ripeFruit, buddingFruit));

        assertThat(grove.isReady()).isFalse();
    }

    @Test
    void getSshConnectionString_returnsFormattedString() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");
        VmPlot plot = createReadyPlot(grove.id());
        grove = grove.withPlot(plot);

        String ssh = grove.getSshConnectionString();

        assertThat(ssh).contains("ssh -o StrictHostKeyChecking=no");
        assertThat(ssh).contains("-o UserKnownHostsFile=/dev/null");
        assertThat(ssh).contains(".ssh/orchard_ed25519");
        assertThat(ssh).contains("-p 2222");
        assertThat(ssh).contains("cultivator@192.168.1.1");
    }

    @Test
    void getSshConnectionString_returnsNullWhenPlotNull() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");

        assertThat(grove.getSshConnectionString()).isNull();
    }

    @Test
    void getSshConnectionString_returnsNullWhenIpNull() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");
        VmPlot plot = VmPlot.germinate(grove.id(), "qemu", SeedlingSpec.from(VmSpec.small()));
        grove = grove.withPlot(plot);

        assertThat(grove.getSshConnectionString()).isNull();
    }

    @Test
    void getSshConnectionString_nullUnlessPlotIsRooted() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");
        VmPlot sapling = createReadyPlot(grove.id());

        assertThat(grove.withPlot(sapling).getSshConnectionString()).isNotNull();
        for (PlotState other : PlotState.values()) {
            if (other != PlotState.ROOTED) {
                assertThat(grove.withPlot(sapling.withState(other)).getSshConnectionString())
                    .as(other.name()).isNull();
            }
        }
    }

    @Test
    void isReady_trueOnlyForRootedPlot() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");
        VmPlot sapling = createReadyPlot(grove.id());
        grove = grove.withState(GroveState.FLOURISHING)
                .withFruit(createRipeFruit(grove.id(), sapling.id()));

        assertThat(grove.withPlot(sapling).isReady()).isTrue();
        for (PlotState other : PlotState.values()) {
            if (other != PlotState.ROOTED) {
                assertThat(grove.withPlot(sapling.withState(other)).isReady()).as(other.name()).isFalse();
            }
        }
        assertThat(grove.withPlot(createContainerPlot(grove.id()).withState(PlotState.ROOTED)).isReady())
                .isTrue();
    }

    @Test
    void sshConnectionString_nullForContainerPlot() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main")
                .withPlot(createContainerPlot(UUID.randomUUID()).withState(PlotState.ROOTED));

        assertThat(grove.getSshConnectionString()).isNull();
    }

    @Test
    void sshReachableVm_presentForRootedVmPlotWithHost() {
        VmPlot plot = createReadyPlot(UUID.randomUUID());
        Grove grove = Grove.plant(cultivatorId, "g", "https://github.com/test/repo", "main").withPlot(plot);

        assertThat(grove.sshReachableVm()).contains(plot);
    }

    @Test
    void sshReachableVm_emptyForNullPlotContainerPlotAndNullHost() {
        Grove grove = Grove.plant(cultivatorId, "g", "https://github.com/test/repo", "main");
        VmPlot noHost = new VmPlot(UUID.randomUUID(), grove.id(), "qemu", "inst-1", PlotState.ROOTED,
                Instant.now(), Instant.now(), null, 2222, List.of(), VmSpec.small());

        assertThat(grove.sshReachableVm()).isEmpty();
        assertThat(grove.withPlot(createContainerPlot(grove.id()).withState(PlotState.ROOTED))
                .sshReachableVm()).isEmpty();
        assertThat(grove.withPlot(noHost).sshReachableVm()).isEmpty();
    }

    @Test
    void sshReachableVm_emptyForEveryNonRootedState() {
        Grove grove = Grove.plant(cultivatorId, "g", "https://github.com/test/repo", "main");
        VmPlot sapling = createReadyPlot(grove.id());
        for (PlotState other : PlotState.values()) {
            if (other != PlotState.ROOTED) {
                assertThat(grove.withPlot(sapling.withState(other)).sshReachableVm()).as(other.name()).isEmpty();
            }
        }
    }

    private VmPlot createReadyPlot(UUID groveId) {
        return new VmPlot(UUID.randomUUID(), groveId, "qemu", "inst-1", PlotState.ROOTED,
                Instant.now(), Instant.now(), "192.168.1.1", 2222, List.of(), VmSpec.small());
    }

    private ContainerPlot createContainerPlot(UUID groveId) {
        return new ContainerPlot(UUID.randomUUID(), groveId, "docker", "c-1", PlotState.GERMINATING,
                Instant.now(), null, Path.of("/srv/groves/g1"), ContainerRuntime.PODMAN,
                new ContainerSpec(2, 4096));
    }

    private Fruit createRipeFruit(UUID groveId, UUID plotId) {
        return Fruit.bud(groveId, plotId,
                Seed.devcontainer().name("test").image("ubuntu").build()).withState(FruitState.RIPE);
    }
}
