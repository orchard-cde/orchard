package dev.orchard.harvest;

import dev.orchard.core.model.DevcontainerSeed;
import dev.orchard.core.model.Seed;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeedSerializerTest {

    private final SeedSerializer serializer = new SeedSerializer();

    @Test
    void roundTrip_writesAtTypeAndDeserializesToDevcontainerSeed() {
        DevcontainerSeed original = DevcontainerSeed.builder()
            .name("test")
            .image("ubuntu:22.04")
            .build();

        String json = serializer.serialize(original);
        assertThat(json).contains("\"@type\"");
        assertThat(json).contains("devcontainer");

        // Verify the JSON structure first to aid debugging
        assertThat(json).contains("\"name\"");

        Seed deserialized = serializer.deserialize(json);
        assertThat(deserialized).isInstanceOf(DevcontainerSeed.class);
        assertThat(deserialized.name()).isEqualTo("test");
        assertThat(deserialized.image()).isEqualTo("ubuntu:22.04");
    }

    @Test
    void deserialize_legacyRowWithoutAtType_fallsBackToDevcontainerSeed() {
        // Old rows written before the refactor have no @type field
        String legacyJson = """
                {
                  "name": "old-workspace",
                  "image": "mcr.microsoft.com/devcontainers/base:ubuntu",
                  "forwardPorts": ["8080"],
                  "containerEnv": {}
                }""";

        Seed deserialized = serializer.deserialize(legacyJson);
        assertThat(deserialized).isInstanceOf(DevcontainerSeed.class);
        assertThat(deserialized.name()).isEqualTo("old-workspace");
        assertThat(deserialized.image()).isEqualTo("mcr.microsoft.com/devcontainers/base:ubuntu");
    }
}
