package dev.orchard.roots.entity;

import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.Seed;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FruitEntityTest {

    @Test
    void roundTrip_preservesComposeProjectName() {
        Seed seed = Seed.devcontainer().image("ubuntu").build();
        Fruit fruit = Fruit.bud(UUID.randomUUID(), UUID.randomUUID(), seed).withComposeProjectName("p1");

        assertThat(FruitEntity.fromModel(fruit).toModel().composeProjectName()).isEqualTo("p1");
    }
}
