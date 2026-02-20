package dev.orchard.core.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

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
    void plant_setsNullSeedlingAndEmptyFruits() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");

        assertThat(grove.seedling()).isNull();
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
    void withSeedling_attachesSeedling() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");
        Seedling seedling = createReadySeedling(grove.id());

        Grove updated = grove.withSeedling(seedling);

        assertThat(updated.seedling()).isEqualTo(seedling);
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
                Seed.builder().name("test").image("ubuntu").build());
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
                null, null, Instant.now(), Instant.now());

        assertThat(grove.primaryFruit()).isNull();
    }

    @Test
    void isReady_trueWhenFullyReady() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");
        Seedling seedling = createReadySeedling(grove.id());
        Fruit fruit = createRipeFruit(grove.id(), seedling.id());

        grove = grove.withState(GroveState.FLOURISHING)
                .withSeedling(seedling)
                .withFruit(fruit);

        assertThat(grove.isReady()).isTrue();
    }

    @Test
    void isReady_falseWhenNotFlourishing() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");
        Seedling seedling = createReadySeedling(grove.id());
        Fruit fruit = createRipeFruit(grove.id(), seedling.id());

        grove = grove.withState(GroveState.GROWING)
                .withSeedling(seedling)
                .withFruit(fruit);

        assertThat(grove.isReady()).isFalse();
    }

    @Test
    void isReady_falseWhenSeedlingNull() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main")
                .withState(GroveState.FLOURISHING)
                .withFruit(createRipeFruit(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(grove.isReady()).isFalse();
    }

    @Test
    void isReady_falseWhenSeedlingNotReady() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");
        Seedling seedling = Seedling.germinate(grove.id(), Seedling.SeedlingSpec.small());

        grove = grove.withState(GroveState.FLOURISHING)
                .withSeedling(seedling)
                .withFruit(createRipeFruit(grove.id(), seedling.id()));

        assertThat(grove.isReady()).isFalse();
    }

    @Test
    void isReady_falseWhenFruitsEmpty() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");
        Seedling seedling = createReadySeedling(grove.id());

        grove = grove.withState(GroveState.FLOURISHING).withSeedling(seedling);

        assertThat(grove.isReady()).isFalse();
    }

    @Test
    void isReady_falseWhenAnyFruitNotReady() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");
        Seedling seedling = createReadySeedling(grove.id());
        Fruit ripeFruit = createRipeFruit(grove.id(), seedling.id());
        Fruit buddingFruit = Fruit.bud(grove.id(), seedling.id(),
                Seed.builder().name("unripe").image("ubuntu").build());

        grove = grove.withState(GroveState.FLOURISHING)
                .withSeedling(seedling)
                .withFruits(List.of(ripeFruit, buddingFruit));

        assertThat(grove.isReady()).isFalse();
    }

    @Test
    void getSshConnectionString_returnsFormattedString() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");
        Seedling seedling = createReadySeedling(grove.id());
        grove = grove.withSeedling(seedling);

        String ssh = grove.getSshConnectionString();

        assertThat(ssh).contains("ssh -o StrictHostKeyChecking=no");
        assertThat(ssh).contains("-o UserKnownHostsFile=/dev/null");
        assertThat(ssh).contains(".ssh/orchard_ed25519");
        assertThat(ssh).contains("-p 2222");
        assertThat(ssh).contains("cultivator@192.168.1.1");
    }

    @Test
    void getSshConnectionString_returnsNullWhenSeedlingNull() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");

        assertThat(grove.getSshConnectionString()).isNull();
    }

    @Test
    void getSshConnectionString_returnsNullWhenIpNull() {
        Grove grove = Grove.plant(cultivatorId, "my-grove", "https://github.com/test/repo", "main");
        Seedling seedling = Seedling.germinate(grove.id(), Seedling.SeedlingSpec.small());
        grove = grove.withSeedling(seedling);

        assertThat(grove.getSshConnectionString()).isNull();
    }

    private Seedling createReadySeedling(UUID groveId) {
        return new Seedling(UUID.randomUUID(), groveId, "inst-1", "192.168.1.1", 2222,
                SeedlingState.SAPLING, Seedling.SeedlingSpec.small(), Instant.now(), Instant.now());
    }

    private Fruit createRipeFruit(UUID groveId, UUID seedlingId) {
        return Fruit.bud(groveId, seedlingId,
                Seed.builder().name("test").image("ubuntu").build()).withState(FruitState.RIPE);
    }
}
