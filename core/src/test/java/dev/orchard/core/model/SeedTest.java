package dev.orchard.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SeedTest {

    @Test
    void builder_defaultsCollectionsToEmpty() {
        DevcontainerSeed seed = DevcontainerSeed.builder().build();

        assertThat(seed.forwardPorts()).isEmpty();
        assertThat(seed.containerEnv()).isEmpty();
    }

    @Test
    void builder_setsBaseFields() {
        DevcontainerSeed seed = DevcontainerSeed.builder()
                .name("java-dev")
                .image("mcr.microsoft.com/devcontainers/java:21")
                .forwardPorts(List.of("8080"))
                .containerEnv(Map.of("JAVA_HOME", "/usr/lib/jvm/java-21"))
                .build();

        assertThat(seed.name()).isEqualTo("java-dev");
        assertThat(seed.image()).isEqualTo("mcr.microsoft.com/devcontainers/java:21");
        assertThat(seed.forwardPorts()).containsExactly("8080");
        assertThat(seed.containerEnv()).containsEntry("JAVA_HOME", "/usr/lib/jvm/java-21");
    }

    @Test
    void seedBuilder_returnsDevcontainerSeed() {
        // Seed.builder() is a convenience alias for DevcontainerSeed.builder()
        DevcontainerSeed seed = Seed.builder().name("test").image("ubuntu").build();

        assertThat(seed).isInstanceOf(DevcontainerSeed.class);
        assertThat(seed.name()).isEqualTo("test");
        assertThat(seed.image()).isEqualTo("ubuntu");
    }
}
