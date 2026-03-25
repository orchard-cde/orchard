package dev.orchard.core.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FruitTest {

    private final UUID groveId = UUID.randomUUID();
    private final UUID seedlingId = UUID.randomUUID();

    @Test
    void bud_createsInBuddingState() {
        Fruit fruit = Fruit.bud(groveId, seedlingId, testSeed());
        assertThat(fruit.state()).isEqualTo(FruitState.BUDDING);
    }

    @Test
    void bud_usesSeedNameAsContainerName() {
        Seed seed = Seed.builder().name("my-container").image("ubuntu").build();
        Fruit fruit = Fruit.bud(groveId, seedlingId, seed);
        assertThat(fruit.containerName()).isEqualTo("my-container");
    }

    @Test
    void bud_usesDefaultNameWhenSeedNameNull() {
        Seed seed = Seed.builder().image("ubuntu").build();
        Fruit fruit = Fruit.bud(groveId, seedlingId, seed);
        assertThat(fruit.containerName()).isEqualTo("orchard-fruit");
    }

    @Test
    void bud_withServiceName() {
        Fruit fruit = Fruit.bud(groveId, seedlingId, testSeed(), "web-service");
        assertThat(fruit.serviceName()).isEqualTo("web-service");
    }

    @Test
    void bud_withoutServiceName_setsNull() {
        Fruit fruit = Fruit.bud(groveId, seedlingId, testSeed());
        assertThat(fruit.serviceName()).isNull();
    }

    @Test
    void bud_setsEmptyPortMappings() {
        Fruit fruit = Fruit.bud(groveId, seedlingId, testSeed());
        assertThat(fruit.portMappings()).isEmpty();
    }

    @Test
    void bud_setsNullContainerIdAndRipenedAt() {
        Fruit fruit = Fruit.bud(groveId, seedlingId, testSeed());
        assertThat(fruit.containerId()).isNull();
        assertThat(fruit.ripenedAt()).isNull();
        assertThat(fruit.buddedAt()).isNotNull();
    }

    @Test
    void withState_toRipe_setsRipenedAt() {
        Fruit fruit = Fruit.bud(groveId, seedlingId, testSeed());
        Instant before = Instant.now();
        Fruit updated = fruit.withState(FruitState.RIPE);
        Instant after = Instant.now();

        assertThat(updated.state()).isEqualTo(FruitState.RIPE);
        assertThat(updated.ripenedAt()).isBetween(before, after);
    }

    @Test
    void withState_toRotted_doesNotSetRipenedAt() {
        Fruit fruit = Fruit.bud(groveId, seedlingId, testSeed());
        Fruit updated = fruit.withState(FruitState.ROTTED);

        assertThat(updated.state()).isEqualTo(FruitState.ROTTED);
        assertThat(updated.ripenedAt()).isNull();
    }

    @Test
    void withContainerDetails_setsContainerIdAndPorts() {
        Fruit fruit = Fruit.bud(groveId, seedlingId, testSeed());
        List<Fruit.PortMapping> ports = List.of(
                new Fruit.PortMapping(8080, 32000, "tcp"),
                new Fruit.PortMapping(3000, 32001, "tcp")
        );

        Fruit updated = fruit.withContainerDetails("container-abc", ports);

        assertThat(updated.containerId()).isEqualTo("container-abc");
        assertThat(updated.portMappings()).hasSize(2);
    }

    @Test
    void isReady_trueOnlyWhenRipe() {
        Fruit fruit = Fruit.bud(groveId, seedlingId, testSeed()).withState(FruitState.RIPE);
        assertThat(fruit.isReady()).isTrue();
    }

    @Test
    void isReady_falseForBudding() {
        Fruit fruit = Fruit.bud(groveId, seedlingId, testSeed());
        assertThat(fruit.isReady()).isFalse();
    }

    private Seed testSeed() {
        return Seed.builder().name("test").image("ubuntu").build();
    }
}
