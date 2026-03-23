package dev.orchard.harvest;

import dev.orchard.core.model.Seed;
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
        Optional<Seed> result = parser.parseJson("""
                {"image": "ubuntu:22.04"}""");

        assertThat(result).isPresent();
        Seed seed = result.get();
        assertThat(seed.name()).isNull();
        assertThat(seed.image()).isEqualTo("ubuntu:22.04");
        assertThat(seed.dockerfilePath()).isNull();
        assertThat(seed.dockerComposeFile()).isNull();
        assertThat(seed.buildArgs()).isEmpty();
        assertThat(seed.features()).isEmpty();
        assertThat(seed.forwardPorts()).isEmpty();
        assertThat(seed.containerEnv()).isEmpty();
        assertThat(seed.postCreateCommands()).isEmpty();
        assertThat(seed.postStartCommands()).isEmpty();
        assertThat(seed.vscodeCustomizations()).isNull();
    }

    @Test
    void parseJson_imageWithName() {
        Optional<Seed> result = parser.parseJson("""
                {"name": "my-dev", "image": "node:18"}""");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("my-dev");
        assertThat(result.get().image()).isEqualTo("node:18");
    }

    @Test
    void parseJson_withForwardPorts() {
        Optional<Seed> result = parser.parseJson("""
                {"image": "node:18", "forwardPorts": [3000, 8080]}""");

        assertThat(result).isPresent();
        assertThat(result.get().forwardPorts()).containsExactly("3000", "8080");
    }

    @Test
    void parseJson_withContainerEnv() {
        Optional<Seed> result = parser.parseJson("""
                {"image": "node:18", "containerEnv": {"NODE_ENV": "development"}}""");

        assertThat(result).isPresent();
        assertThat(result.get().containerEnv()).containsEntry("NODE_ENV", "development");
    }

    @Test
    void parseJson_dockerfileTopLevel() {
        Optional<Seed> result = parser.parseJson("""
                {"dockerFile": "Dockerfile"}""");

        assertThat(result).isPresent();
        assertThat(result.get().dockerfilePath()).isEqualTo("Dockerfile");
    }

    @Test
    void parseJson_dockerfileInBuildObject() {
        Optional<Seed> result = parser.parseJson("""
                {"build": {"dockerfile": "Dockerfile"}}""");

        assertThat(result).isPresent();
        assertThat(result.get().dockerfilePath()).isEqualTo("Dockerfile");
    }

    @Test
    void parseJson_dockerfileWithBuildArgs() {
        Optional<Seed> result = parser.parseJson("""
                {"build": {"dockerfile": "Dockerfile", "args": {"VARIANT": "3.11"}}}""");

        assertThat(result).isPresent();
        assertThat(result.get().dockerfilePath()).isEqualTo("Dockerfile");
        assertThat(result.get().buildArgs()).containsEntry("VARIANT", "3.11");
    }

    @Test
    void parseJson_dockerComposeFileString() {
        Optional<Seed> result = parser.parseJson("""
                {"dockerComposeFile": "docker-compose.yml", "service": "app"}""");

        assertThat(result).isPresent();
        assertThat(result.get().dockerComposeFile()).isEqualTo("docker-compose.yml");
        assertThat(result.get().service()).isEqualTo("app");
    }

    @Test
    void parseJson_dockerComposeFileArray() {
        Optional<Seed> result = parser.parseJson("""
                {"dockerComposeFile": ["docker-compose.yml", "docker-compose.dev.yml"], "service": "app"}""");

        assertThat(result).isPresent();
        assertThat(result.get().dockerComposeFile()).isEqualTo("docker-compose.yml");
    }

    @Test
    void parseJson_features() {
        Optional<Seed> result = parser.parseJson("""
                {"image": "ubuntu", "features": {"ghcr.io/devcontainers/features/node:1": {}, "ghcr.io/devcontainers/features/python:1": {}}}""");

        assertThat(result).isPresent();
        assertThat(result.get().features()).containsExactlyInAnyOrder(
                "ghcr.io/devcontainers/features/node:1",
                "ghcr.io/devcontainers/features/python:1"
        );
    }

    @Test
    void parseJson_postCreateCommandString() {
        Optional<Seed> result = parser.parseJson("""
                {"image": "ubuntu", "postCreateCommand": "npm install"}""");

        assertThat(result).isPresent();
        assertThat(result.get().postCreateCommands()).containsExactly("npm install");
    }

    @Test
    void parseJson_postCreateCommandArray() {
        Optional<Seed> result = parser.parseJson("""
                {"image": "ubuntu", "postCreateCommand": ["npm install", "npm run build"]}""");

        assertThat(result).isPresent();
        assertThat(result.get().postCreateCommands()).containsExactly("npm install", "npm run build");
    }

    @Test
    void parseJson_postStartCommand() {
        Optional<Seed> result = parser.parseJson("""
                {"image": "ubuntu", "postStartCommand": "npm start"}""");

        assertThat(result).isPresent();
        assertThat(result.get().postStartCommands()).containsExactly("npm start");
    }

    @Test
    void parseJson_vscodeCustomizations() {
        Optional<Seed> result = parser.parseJson("""
                {"image": "ubuntu", "customizations": {"vscode": {"extensions": ["ms-python.python"], "settings": {"editor.fontSize": 14}}}}""");

        assertThat(result).isPresent();
        Seed.VsCodeCustomizations customizations = result.get().vscodeCustomizations();
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

        Optional<Seed> result = parser.parseJson(json);

        assertThat(result).isPresent();
        Seed seed = result.get();
        assertThat(seed.name()).isEqualTo("Full Dev Environment");
        assertThat(seed.image()).isEqualTo("mcr.microsoft.com/devcontainers/base:ubuntu");
        assertThat(seed.features()).hasSize(2);
        assertThat(seed.forwardPorts()).containsExactly("3000", "5432", "8080");
        assertThat(seed.containerEnv()).hasSize(2);
        assertThat(seed.postCreateCommands()).containsExactly("npm install", "gradle build");
        assertThat(seed.postStartCommands()).containsExactly("npm run dev");
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
    void parseJson_singleLineComment() {
        Optional<Seed> result = parser.parseJson("""
                {
                  // Use the official Node image
                  "image": "node:18"
                }""");

        assertThat(result).isPresent();
        assertThat(result.get().image()).isEqualTo("node:18");
    }

    @Test
    void parseJson_blockComment() {
        Optional<Seed> result = parser.parseJson("""
                {
                  /* primary image */
                  "image": "ubuntu:22.04",
                  "name": /* dev env */ "my-dev"
                }""");

        assertThat(result).isPresent();
        assertThat(result.get().image()).isEqualTo("ubuntu:22.04");
        assertThat(result.get().name()).isEqualTo("my-dev");
    }

    @Test
    void parseJson_trailingComma() {
        Optional<Seed> result = parser.parseJson("""
                {
                  "image": "node:18",
                  "forwardPorts": [3000, 8080,],
                }""");

        assertThat(result).isPresent();
        assertThat(result.get().image()).isEqualTo("node:18");
        assertThat(result.get().forwardPorts()).containsExactly("3000", "8080");
    }

    @Test
    void parseJson_emptyObject() {
        Optional<Seed> result = parser.parseJson("{}");

        assertThat(result).isPresent();
        Seed seed = result.get();
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

        Optional<Seed> result = parser.discover(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get().image()).isEqualTo("ubuntu:22.04");
    }

    @Test
    void discover_findsAtRoot(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve(".devcontainer.json"),
                """
                {"image": "node:18"}""");

        Optional<Seed> result = parser.discover(tempDir);

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

        Optional<Seed> result = parser.discover(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get().image()).isEqualTo("ubuntu:22.04");
    }

    @Test
    void discover_returnsEmptyWhenNotFound(@TempDir Path tempDir) throws IOException {
        assertThat(parser.discover(tempDir)).isEmpty();
    }

    @Test
    void discover_findsInSubfolder(@TempDir Path tempDir) throws IOException {
        Path subfolderDir = tempDir.resolve(".devcontainer/java");
        Files.createDirectories(subfolderDir);
        Files.writeString(subfolderDir.resolve("devcontainer.json"),
                """
                {"image": "eclipse-temurin:21"}""");

        Optional<Seed> result = parser.discover(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get().image()).isEqualTo("eclipse-temurin:21");
    }

    @Test
    void discover_rootTakesPrecedenceOverSubfolder(@TempDir Path tempDir) throws IOException {
        Path devcontainerDir = tempDir.resolve(".devcontainer");
        Files.createDirectories(devcontainerDir);
        Files.writeString(devcontainerDir.resolve("devcontainer.json"),
                """
                {"image": "ubuntu:22.04"}""");
        Path subfolderDir = devcontainerDir.resolve("java");
        Files.createDirectories(subfolderDir);
        Files.writeString(subfolderDir.resolve("devcontainer.json"),
                """
                {"image": "eclipse-temurin:21"}""");

        Optional<Seed> result = parser.discover(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get().image()).isEqualTo("ubuntu:22.04");
    }

    @Test
    void discover_firstAlphabeticalSubfolderWins(@TempDir Path tempDir) throws IOException {
        Path alphaDir = tempDir.resolve(".devcontainer/alpha");
        Path zetaDir = tempDir.resolve(".devcontainer/zeta");
        Files.createDirectories(alphaDir);
        Files.createDirectories(zetaDir);
        Files.writeString(alphaDir.resolve("devcontainer.json"),
                """
                {"image": "alpha-image"}""");
        Files.writeString(zetaDir.resolve("devcontainer.json"),
                """
                {"image": "zeta-image"}""");

        Optional<Seed> result = parser.discover(tempDir);

        assertThat(result).isPresent();
        assertThat(result.get().image()).isEqualTo("alpha-image");
    }

    @Test
    void discoverAll_emptyWhenNotFound(@TempDir Path tempDir) throws IOException {
        assertThat(parser.discoverAll(tempDir)).isEmpty();
    }

    @Test
    void discoverAll_rootDevcontainerDir(@TempDir Path tempDir) throws IOException {
        Path devcontainerDir = tempDir.resolve(".devcontainer");
        Files.createDirectories(devcontainerDir);
        Files.writeString(devcontainerDir.resolve("devcontainer.json"),
                """
                {"image": "ubuntu:22.04"}""");

        Map<String, Seed> result = parser.discoverAll(tempDir);

        assertThat(result).containsOnlyKeys("default");
        assertThat(result.get("default").image()).isEqualTo("ubuntu:22.04");
    }

    @Test
    void discoverAll_rootDevcontainerJson(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve(".devcontainer.json"),
                """
                {"image": "node:18"}""");

        Map<String, Seed> result = parser.discoverAll(tempDir);

        assertThat(result).containsOnlyKeys("default");
        assertThat(result.get("default").image()).isEqualTo("node:18");
    }

    @Test
    void discoverAll_higherPriorityRootWins(@TempDir Path tempDir) throws IOException {
        Path devcontainerDir = tempDir.resolve(".devcontainer");
        Files.createDirectories(devcontainerDir);
        Files.writeString(devcontainerDir.resolve("devcontainer.json"),
                """
                {"image": "ubuntu:22.04"}""");
        Files.writeString(tempDir.resolve(".devcontainer.json"),
                """
                {"image": "node:18"}""");

        Map<String, Seed> result = parser.discoverAll(tempDir);

        assertThat(result).containsOnlyKeys("default");
        assertThat(result.get("default").image()).isEqualTo("ubuntu:22.04");
    }

    @Test
    void discoverAll_subfolderKeyedByFolderName(@TempDir Path tempDir) throws IOException {
        Path javaDir = tempDir.resolve(".devcontainer/java");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("devcontainer.json"),
                """
                {"image": "eclipse-temurin:21"}""");

        Map<String, Seed> result = parser.discoverAll(tempDir);

        assertThat(result).containsOnlyKeys("java");
        assertThat(result.get("java").image()).isEqualTo("eclipse-temurin:21");
    }

    @Test
    void discoverAll_rootAndSubfoldersTogether(@TempDir Path tempDir) throws IOException {
        Path devcontainerDir = tempDir.resolve(".devcontainer");
        Files.createDirectories(devcontainerDir);
        Files.writeString(devcontainerDir.resolve("devcontainer.json"),
                """
                {"image": "ubuntu:22.04"}""");
        Path javaDir = devcontainerDir.resolve("java");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("devcontainer.json"),
                """
                {"image": "eclipse-temurin:21"}""");

        Map<String, Seed> result = parser.discoverAll(tempDir);

        assertThat(result).containsKeys("default", "java");
        assertThat(result.get("default").image()).isEqualTo("ubuntu:22.04");
        assertThat(result.get("java").image()).isEqualTo("eclipse-temurin:21");
    }

    @Test
    void discoverAll_multipleSubfoldersOrderedAlphabetically(@TempDir Path tempDir) throws IOException {
        for (String name : List.of("python", "java", "node")) {
            Path dir = tempDir.resolve(".devcontainer/" + name);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("devcontainer.json"),
                    "{\"image\": \"" + name + "-image\"}");
        }

        Map<String, Seed> result = parser.discoverAll(tempDir);

        assertThat(result.keySet()).containsExactly("java", "node", "python");
    }

    @Test
    void discoverAll_excludesFolderNamedDefault(@TempDir Path tempDir) throws IOException {
        Path defaultDir = tempDir.resolve(".devcontainer/default");
        Files.createDirectories(defaultDir);
        Files.writeString(defaultDir.resolve("devcontainer.json"),
                """
                {"image": "should-not-appear"}""");
        Path javaDir = tempDir.resolve(".devcontainer/java");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("devcontainer.json"),
                """
                {"image": "eclipse-temurin:21"}""");

        Map<String, Seed> result = parser.discoverAll(tempDir);

        assertThat(result).containsOnlyKeys("java");
        assertThat(result).doesNotContainKey("default");
    }
}
