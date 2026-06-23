package dev.orchard.harvest;

import dev.orchard.core.model.DevcontainerSeed;
import dev.orchard.core.model.LifecycleCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DevcontainerParserTest {

    private final DevcontainerParser parser = new DevcontainerParser();

    @Test
    void parseJson_minimalImage() {
        Optional<DevcontainerSeed> result = parser.parseJson("""
                {"image": "ubuntu:22.04"}""");

        assertThat(result).isPresent();
        DevcontainerSeed seed = result.get();
        assertThat(seed.name()).isNull();
        assertThat(seed.image()).isEqualTo("ubuntu:22.04");
        assertThat(seed.dockerfilePath()).isNull();
        assertThat(seed.dockerComposeFile()).isNull();
        assertThat(seed.buildArgs()).isEmpty();
        assertThat(seed.features()).isEmpty();
        assertThat(seed.forwardPorts()).isEmpty();
        assertThat(seed.containerEnv()).isEmpty();
        assertThat(seed.postCreateCommand()).isNull();
        assertThat(seed.postStartCommand()).isNull();
        assertThat(seed.vscodeCustomizations()).isNull();
    }

    @Test
    void parseJson_imageWithName() {
        Optional<DevcontainerSeed> result = parser.parseJson("""
                {"name": "my-dev", "image": "node:18"}""");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("my-dev");
        assertThat(result.get().image()).isEqualTo("node:18");
    }

    @Test
    void parseJson_withForwardPorts() {
        Optional<DevcontainerSeed> result = parser.parseJson("""
                {"image": "node:18", "forwardPorts": [3000, 8080]}""");

        assertThat(result).isPresent();
        assertThat(result.get().forwardPorts()).containsExactly("3000", "8080");
    }

    @Test
    void parseJson_withContainerEnv() {
        Optional<DevcontainerSeed> result = parser.parseJson("""
                {"image": "node:18", "containerEnv": {"NODE_ENV": "development"}}""");

        assertThat(result).isPresent();
        assertThat(result.get().containerEnv()).containsEntry("NODE_ENV", "development");
    }

    @Test
    void parseJson_dockerfileTopLevel() {
        Optional<DevcontainerSeed> result = parser.parseJson("""
                {"dockerFile": "Dockerfile"}""");

        assertThat(result).isPresent();
        assertThat(result.get().dockerfilePath()).isEqualTo("Dockerfile");
    }

    @Test
    void parseJson_dockerfileInBuildObject() {
        Optional<DevcontainerSeed> result = parser.parseJson("""
                {"build": {"dockerfile": "Dockerfile"}}""");

        assertThat(result).isPresent();
        assertThat(result.get().dockerfilePath()).isEqualTo("Dockerfile");
    }

    @Test
    void parseJson_dockerfileWithBuildArgs() {
        Optional<DevcontainerSeed> result = parser.parseJson("""
                {"build": {"dockerfile": "Dockerfile", "args": {"VARIANT": "3.11"}}}""");

        assertThat(result).isPresent();
        assertThat(result.get().dockerfilePath()).isEqualTo("Dockerfile");
        assertThat(result.get().buildArgs()).containsEntry("VARIANT", "3.11");
    }

    @Test
    void parseJson_dockerComposeFileString() {
        Optional<DevcontainerSeed> result = parser.parseJson("""
                {"dockerComposeFile": "docker-compose.yml", "service": "app"}""");

        assertThat(result).isPresent();
        assertThat(result.get().dockerComposeFile()).isEqualTo("docker-compose.yml");
        assertThat(result.get().service()).isEqualTo("app");
    }

    @Test
    void parseJson_dockerComposeFileArray() {
        Optional<DevcontainerSeed> result = parser.parseJson("""
                {"dockerComposeFile": ["docker-compose.yml", "docker-compose.dev.yml"], "service": "app"}""");

        assertThat(result).isPresent();
        assertThat(result.get().dockerComposeFile()).isEqualTo("docker-compose.yml");
    }

    @Test
    void parseJson_features() {
        Optional<DevcontainerSeed> result = parser.parseJson("""
                {"image": "ubuntu", "features": {"ghcr.io/devcontainers/features/node:1": {}, "ghcr.io/devcontainers/features/python:1": {}}}""");

        assertThat(result).isPresent();
        assertThat(result.get().features()).containsOnlyKeys(
                "ghcr.io/devcontainers/features/node:1",
                "ghcr.io/devcontainers/features/python:1"
        );
        assertThat(result.get().features().keySet()).containsExactly(
                "ghcr.io/devcontainers/features/node:1",
                "ghcr.io/devcontainers/features/python:1"
        );
    }

    @Test
    void parseJson_featureOptionsPreserved() {
        Optional<DevcontainerSeed> result = parser.parseJson("""
                {"image": "ubuntu", "features": {"ghcr.io/devcontainers/features/java:1": {"version": "21"}}}""");

        assertThat(result).isPresent();
        assertThat(result.get().features())
                .containsEntry("ghcr.io/devcontainers/features/java:1", Map.of("version", "21"));
    }

    @Test
    void parseJson_featureWithEmptyOptionsIsEmptyNotNull() {
        Optional<DevcontainerSeed> result = parser.parseJson("""
                {"image": "ubuntu", "features": {"ghcr.io/devcontainers/features/node:1": {}}}""");

        assertThat(result).isPresent();
        Map<String, Object> options = result.get().features().get("ghcr.io/devcontainers/features/node:1");
        assertThat(options).isNotNull().isEmpty();
    }

    @Test
    void parseJson_featureOptionsHeterogeneousValues() {
        Optional<DevcontainerSeed> result = parser.parseJson("""
                {"image": "ubuntu", "features": {"example/feature:1": {"name": "alice", "enabled": true, "count": 3}}}""");

        assertThat(result).isPresent();
        Map<String, Object> options = result.get().features().get("example/feature:1");
        assertThat(options)
                .containsEntry("name", "alice")
                .containsEntry("enabled", true)
                .containsEntry("count", 3);
    }

    @Test
    void parseJson_postCreateCommandString() {
        Optional<DevcontainerSeed> result = parser.parseJson("""
                {"image": "ubuntu", "postCreateCommand": "npm install"}""");

        assertThat(result).isPresent();
        assertThat(result.get().postCreateCommand())
            .isEqualTo(new LifecycleCommand.Sequential(List.of("npm install")));
    }

    @Test
    void parseJson_postCreateCommandArray() {
        Optional<DevcontainerSeed> result = parser.parseJson("""
                {"image": "ubuntu", "postCreateCommand": ["npm install", "npm run build"]}""");

        assertThat(result).isPresent();
        assertThat(result.get().postCreateCommand())
            .isEqualTo(new LifecycleCommand.Sequential(List.of("npm install", "npm run build")));
    }

    @Test
    void parseJson_postStartCommand() {
        Optional<DevcontainerSeed> result = parser.parseJson("""
                {"image": "ubuntu", "postStartCommand": "npm start"}""");

        assertThat(result).isPresent();
        assertThat(result.get().postStartCommand())
            .isEqualTo(new LifecycleCommand.Sequential(List.of("npm start")));
    }

    @Test
    void parseJson_vscodeCustomizations() {
        Optional<DevcontainerSeed> result = parser.parseJson("""
                {"image": "ubuntu", "customizations": {"vscode": {"extensions": ["ms-python.python"], "settings": {"editor.fontSize": 14}}}}""");

        assertThat(result).isPresent();
        DevcontainerSeed.VsCodeCustomizations customizations = result.get().vscodeCustomizations();
        assertThat(customizations).isNotNull();
        assertThat(customizations.extensions()).containsExactly("ms-python.python");
        assertThat(customizations.settings()).containsEntry("editor.fontSize", 14);
    }

    @Test
    void parseJson_fullSpec() {
        String json = """
                {
                  "name": "Full Dev Environment",
                  "image": "mcr.microsoft.com/devcontainers/base:ubuntu",
                  "features": {
                    "ghcr.io/devcontainers/features/node:1": {},
                    "ghcr.io/devcontainers/features/java:1": {"version": "21"}
                  },
                  "forwardPorts": [3000, 5432, 8080],
                  "containerEnv": {
                    "DATABASE_URL": "postgres://localhost:5432/dev",
                    "NODE_ENV": "development"
                  },
                  "postCreateCommand": ["npm install", "gradle build"],
                  "postStartCommand": "npm run dev",
                  "customizations": {
                    "vscode": {
                      "extensions": ["ms-python.python", "dbaeumer.vscode-eslint"],
                      "settings": {"editor.formatOnSave": true}
                    }
                  }
                }
                """;

        Optional<DevcontainerSeed> result = parser.parseJson(json);

        assertThat(result).isPresent();
        DevcontainerSeed seed = result.get();
        assertThat(seed.name()).isEqualTo("Full Dev Environment");
        assertThat(seed.image()).isEqualTo("mcr.microsoft.com/devcontainers/base:ubuntu");
        assertThat(seed.features()).hasSize(2);
        assertThat(seed.features()).containsEntry("ghcr.io/devcontainers/features/java:1", Map.of("version", "21"));
        assertThat(seed.features().get("ghcr.io/devcontainers/features/node:1")).isNotNull().isEmpty();
        assertThat(seed.forwardPorts()).containsExactly("3000", "5432", "8080");
        assertThat(seed.containerEnv()).hasSize(2);
        assertThat(seed.postCreateCommand())
            .isEqualTo(new LifecycleCommand.Sequential(List.of("npm install", "gradle build")));
        assertThat(seed.postStartCommand())
            .isEqualTo(new LifecycleCommand.Sequential(List.of("npm run dev")));
        assertThat(seed.vscodeCustomizations().extensions()).hasSize(2);
    }

    @Test
    void parseJson_nullContent_returnsEmpty() {
        assertThat(parser.parseJson(null)).isEmpty();
    }

    @Test
    void parseJson_blankContent_returnsEmpty() {
        assertThat(parser.parseJson("   ")).isEmpty();
    }

    @Test
    void parseJson_invalidJson_returnsEmpty() {
        assertThat(parser.parseJson("not valid json {{{")).isEmpty();
    }

    @Test
    void parseJson_emptyObject() {
        Optional<DevcontainerSeed> result = parser.parseJson("{}");

        assertThat(result).isPresent();
        DevcontainerSeed seed = result.get();
        assertThat(seed.name()).isNull();
        assertThat(seed.image()).isNull();
        assertThat(seed.dockerfilePath()).isNull();
    }

    @Test
    void discover_findsInDevcontainerSubdir(@TempDir Path tempDir) throws IOException {
        Path devcontainerDir = tempDir.resolve(".devcontainer");
        Files.createDirectories(devcontainerDir);
        Files.writeString(devcontainerDir.resolve("devcontainer.json"),
                """
                {"image": "ubuntu:22.04"}""");

        Optional<DevcontainerSeed> result = parser.discover(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get().image()).isEqualTo("ubuntu:22.04");
    }

    @Test
    void discover_findsAtRoot(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve(".devcontainer.json"),
                """
                {"image": "node:18"}""");

        Optional<DevcontainerSeed> result = parser.discover(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get().image()).isEqualTo("node:18");
    }

    @Test
    void discover_prefersSubdirOverRoot(@TempDir Path tempDir) throws IOException {
        Path devcontainerDir = tempDir.resolve(".devcontainer");
        Files.createDirectories(devcontainerDir);
        Files.writeString(devcontainerDir.resolve("devcontainer.json"),
                """
                {"image": "ubuntu:22.04"}""");
        Files.writeString(tempDir.resolve(".devcontainer.json"),
                """
                {"image": "node:18"}""");

        Optional<DevcontainerSeed> result = parser.discover(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get().image()).isEqualTo("ubuntu:22.04");
    }

    @Test
    void discover_returnsEmptyWhenNotFound(@TempDir Path tempDir) throws IOException {
        assertThat(parser.discover(tempDir)).isEmpty();
    }
}
