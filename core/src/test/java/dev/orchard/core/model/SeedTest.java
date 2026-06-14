package dev.orchard.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SeedTest {

    @Test
    void builder_defaultsCollectionsToEmpty() {
        Seed seed = Seed.builder().build();

        assertThat(seed.buildArgs()).isEmpty();
        assertThat(seed.features()).isEmpty();
        assertThat(seed.forwardPorts()).isEmpty();
        assertThat(seed.containerEnv()).isEmpty();
        assertThat(seed.postCreateCommand()).isNull();
        assertThat(seed.postStartCommand()).isNull();
        assertThat(seed.initializeCommand()).isNull();
        assertThat(seed.onCreateCommand()).isNull();
        assertThat(seed.updateContentCommand()).isNull();
        assertThat(seed.postAttachCommand()).isNull();
        assertThat(seed.waitFor()).isNull();
    }

    @Test
    void builder_setsAllFields() {
        Seed.VsCodeCustomizations vscode = new Seed.VsCodeCustomizations(
                List.of("ms-java.vscode-java-pack"), Map.of("java.home", "/usr/lib/jvm/java-21"));

        Seed seed = Seed.builder()
                .name("java-dev")
                .image("mcr.microsoft.com/devcontainers/java:21")
                .dockerfilePath("Dockerfile")
                .dockerComposeFile("docker-compose.yml")
                .service("app")
                .buildArgs(Map.of("VARIANT", "21"))
                .features(Map.of("ghcr.io/devcontainers/features/java:1", Map.of()))
                .forwardPorts(List.of("8080"))
                .containerEnv(Map.of("JAVA_HOME", "/usr/lib/jvm/java-21"))
                .postCreateCommand(new LifecycleCommand.Sequential(List.of("./gradlew build")))
                .postStartCommand(new LifecycleCommand.Sequential(List.of("./gradlew bootRun")))
                .vscodeCustomizations(vscode)
                .build();

        assertThat(seed.name()).isEqualTo("java-dev");
        assertThat(seed.image()).isEqualTo("mcr.microsoft.com/devcontainers/java:21");
        assertThat(seed.dockerfilePath()).isEqualTo("Dockerfile");
        assertThat(seed.dockerComposeFile()).isEqualTo("docker-compose.yml");
        assertThat(seed.service()).isEqualTo("app");
        assertThat(seed.buildArgs()).containsEntry("VARIANT", "21");
        assertThat(seed.features()).containsOnlyKeys("ghcr.io/devcontainers/features/java:1");
        assertThat(seed.vscodeCustomizations()).isEqualTo(vscode);
    }

    @Test
    void builder_minimalImageSeed() {
        Seed seed = Seed.builder().name("minimal").image("ubuntu:22.04").build();

        assertThat(seed.name()).isEqualTo("minimal");
        assertThat(seed.image()).isEqualTo("ubuntu:22.04");
        assertThat(seed.dockerfilePath()).isNull();
        assertThat(seed.dockerComposeFile()).isNull();
        assertThat(seed.service()).isNull();
        assertThat(seed.vscodeCustomizations()).isNull();
    }

    @Test
    void seed_effectiveWaitFor_returnsWaitForIfNotNull() {
        WaitFor customWait = WaitFor.INITIALIZE_COMMAND;

        Seed seed = Seed.builder()
                .initializeCommand(new LifecycleCommand.Sequential(List.of("init")))
                .waitFor(customWait)
                .build();

        assertThat(seed.effectiveWaitFor()).isEqualTo(customWait);
    }

    @Test
    void seed_effectiveWaitFor_returnsDefaultIfNull() {
        // Build seed without setting waitFor
        Seed seed = Seed.builder()
                .initializeCommand(new LifecycleCommand.Parallel(Map.of("step1", List.of("cmd1"))))
                .build();

        // Should default to UPDATE_CONTENT_COMMAND if waitFor is null
        assertThat(seed.effectiveWaitFor()).isEqualTo(WaitFor.UPDATE_CONTENT_COMMAND);
    }

}
