package dev.orchard.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerRuntimeTest {

    @Test
    void cliNamesAreExplicit() {
        assertThat(ContainerRuntime.DOCKER.cli()).isEqualTo("docker");
        assertThat(ContainerRuntime.PODMAN.cli()).isEqualTo("podman");
    }
}
