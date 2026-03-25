package dev.orchard.core.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SeedlingTest {

    private final UUID groveId = UUID.randomUUID();

    @Test
    void germinate_createsInGerminatingState() {
        Seedling seedling = Seedling.germinate(groveId, Seedling.SeedlingSpec.small());
        assertThat(seedling.state()).isEqualTo(SeedlingState.GERMINATING);
    }

    @Test
    void germinate_setsDefaultPort22() {
        Seedling seedling = Seedling.germinate(groveId, Seedling.SeedlingSpec.small());
        assertThat(seedling.sshPort()).isEqualTo(22);
    }

    @Test
    void germinate_setsNullIpAndProviderInstanceId() {
        Seedling seedling = Seedling.germinate(groveId, Seedling.SeedlingSpec.small());
        assertThat(seedling.ipAddress()).isNull();
        assertThat(seedling.providerInstanceId()).isNull();
    }

    @Test
    void germinate_recordsPlantedAt() {
        Instant before = Instant.now();
        Seedling seedling = Seedling.germinate(groveId, Seedling.SeedlingSpec.small());
        Instant after = Instant.now();
        assertThat(seedling.plantedAt()).isBetween(before, after);
    }

    @Test
    void germinate_setsNullReadyAt() {
        Seedling seedling = Seedling.germinate(groveId, Seedling.SeedlingSpec.small());
        assertThat(seedling.readyAt()).isNull();
    }

    @Test
    void smallSpec_hasCorrectValues() {
        var spec = Seedling.SeedlingSpec.small();
        assertThat(spec.cpuCores()).isEqualTo(2);
        assertThat(spec.memoryMb()).isEqualTo(4096);
        assertThat(spec.diskGb()).isEqualTo(20);
        assertThat(spec.machineType()).isEqualTo("small");
    }

    @Test
    void mediumSpec_hasCorrectValues() {
        var spec = Seedling.SeedlingSpec.medium();
        assertThat(spec.cpuCores()).isEqualTo(4);
        assertThat(spec.memoryMb()).isEqualTo(8192);
        assertThat(spec.diskGb()).isEqualTo(40);
        assertThat(spec.machineType()).isEqualTo("medium");
    }

    @Test
    void largeSpec_hasCorrectValues() {
        var spec = Seedling.SeedlingSpec.large();
        assertThat(spec.cpuCores()).isEqualTo(8);
        assertThat(spec.memoryMb()).isEqualTo(16384);
        assertThat(spec.diskGb()).isEqualTo(80);
        assertThat(spec.machineType()).isEqualTo("large");
    }

    @Test
    void withState_toSapling_setsReadyAt() {
        Seedling seedling = Seedling.germinate(groveId, Seedling.SeedlingSpec.small());
        Instant before = Instant.now();
        Seedling updated = seedling.withState(SeedlingState.SAPLING);
        Instant after = Instant.now();

        assertThat(updated.state()).isEqualTo(SeedlingState.SAPLING);
        assertThat(updated.readyAt()).isBetween(before, after);
    }

    @Test
    void withState_toSprouting_doesNotSetReadyAt() {
        Seedling seedling = Seedling.germinate(groveId, Seedling.SeedlingSpec.small());
        Seedling updated = seedling.withState(SeedlingState.SPROUTING);

        assertThat(updated.state()).isEqualTo(SeedlingState.SPROUTING);
        assertThat(updated.readyAt()).isNull();
    }

    @Test
    void withState_toBlighted_doesNotSetReadyAt() {
        Seedling seedling = Seedling.germinate(groveId, Seedling.SeedlingSpec.small());
        Seedling updated = seedling.withState(SeedlingState.BLIGHTED);

        assertThat(updated.state()).isEqualTo(SeedlingState.BLIGHTED);
        assertThat(updated.readyAt()).isNull();
    }

    @Test
    void withProviderDetails_setsInstanceIdAndIp() {
        Seedling seedling = Seedling.germinate(groveId, Seedling.SeedlingSpec.small());
        Seedling updated = seedling.withProviderDetails("i-12345", "10.0.0.1");

        assertThat(updated.providerInstanceId()).isEqualTo("i-12345");
        assertThat(updated.ipAddress()).isEqualTo("10.0.0.1");
    }

    @Test
    void withProviderDetails_preservesOtherFields() {
        Seedling seedling = Seedling.germinate(groveId, Seedling.SeedlingSpec.medium());
        Seedling updated = seedling.withProviderDetails("i-12345", "10.0.0.1");

        assertThat(updated.id()).isEqualTo(seedling.id());
        assertThat(updated.groveId()).isEqualTo(groveId);
        assertThat(updated.sshPort()).isEqualTo(22);
        assertThat(updated.state()).isEqualTo(SeedlingState.GERMINATING);
        assertThat(updated.spec()).isEqualTo(Seedling.SeedlingSpec.medium());
    }

    @Test
    void isReady_trueOnlyWhenSapling() {
        Seedling seedling = Seedling.germinate(groveId, Seedling.SeedlingSpec.small())
                .withState(SeedlingState.SAPLING);
        assertThat(seedling.isReady()).isTrue();
    }

    @Test
    void isReady_falseForGerminating() {
        Seedling seedling = Seedling.germinate(groveId, Seedling.SeedlingSpec.small());
        assertThat(seedling.isReady()).isFalse();
    }
}
