package dev.orchard.api.dto;

import dev.orchard.core.model.*;
import dev.orchard.core.model.Seedling.SeedlingSpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GroveResponseTest {

    @Test
    void fromModel_mapsAllFields() {
        UUID groveId = UUID.randomUUID();
        UUID cultivatorId = UUID.randomUUID();
        UUID seedlingId = UUID.randomUUID();

        Seedling seedling = new Seedling(
            seedlingId, groveId, "inst-1", "192.168.1.1", 2222,
            SeedlingState.SAPLING, SeedlingSpec.small(), Instant.now(), Instant.now()
        );

        Seed seed = Seed.devcontainer().name("test").image("ubuntu").build();
        Fruit fruit = Fruit.bud(groveId, seedlingId, seed).withState(FruitState.RIPE)
            .withContainerDetails("abc123", List.of(new Fruit.PortMapping(8080, 8080, "tcp")));

        Grove grove = new Grove(
            groveId, cultivatorId, "my-grove", "https://github.com/user/repo", "main",
            "abc1234", GroveState.FLOURISHING, seedling, List.of(fruit),
            Instant.now(), Instant.now()
        );

        GroveResponse response = GroveResponse.fromModel(grove);

        assertThat(response.id()).isEqualTo(groveId);
        assertThat(response.name()).isEqualTo("my-grove");
        assertThat(response.repositoryUrl()).isEqualTo("https://github.com/user/repo");
        assertThat(response.branch()).isEqualTo("main");
        assertThat(response.commitSha()).isEqualTo("abc1234");
        assertThat(response.state()).isEqualTo(GroveState.FLOURISHING);
        assertThat(response.seedling()).isNotNull();
        assertThat(response.seedling().id()).isEqualTo(seedlingId);
        assertThat(response.seedling().state()).isEqualTo("SAPLING");
        assertThat(response.seedling().ipAddress()).isEqualTo("192.168.1.1");
        assertThat(response.seedling().sshPort()).isEqualTo(2222);
        assertThat(response.seedling().cpuCores()).isEqualTo(2);
        assertThat(response.seedling().memoryMb()).isEqualTo(4096);
        assertThat(response.seedling().diskGb()).isEqualTo(20);
        assertThat(response.fruits()).hasSize(1);
        assertThat(response.fruits().getFirst().state()).isEqualTo("RIPE");
        assertThat(response.fruits().getFirst().containerId()).isEqualTo("abc123");
    }

    @Test
    void fromModel_handlesNullSeedling() {
        Grove grove = Grove.plant(UUID.randomUUID(), "test", "https://github.com/user/repo", "main");

        GroveResponse response = GroveResponse.fromModel(grove);

        assertThat(response.seedling()).isNull();
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
}
