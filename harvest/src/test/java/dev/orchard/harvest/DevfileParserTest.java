package dev.orchard.harvest;

import dev.orchard.core.model.DevfileSeed;
import dev.orchard.harvest.DevfileParser.DevfileParseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevfileParserTest {

    private final DevfileParser parser = new DevfileParser();

    // --- parseYaml: happy paths --------------------------------------------------------------

    @Test
    void parseYaml_minimalContainer() {
        Optional<DevfileSeed> result = parser.parseYaml("""
                schemaVersion: "2.2.0"
                metadata:
                  name: my-workspace
                components:
                  - name: dev
                    container:
                      image: quay.io/devfile/universal-developer-image:latest
                """);

        assertThat(result).isPresent();
        DevfileSeed seed = result.get();
        assertThat(seed.name()).isEqualTo("my-workspace");
        assertThat(seed.componentName()).isEqualTo("dev");
        assertThat(seed.image()).isEqualTo("quay.io/devfile/universal-developer-image:latest");
        assertThat(seed.forwardPorts()).isEmpty();
        assertThat(seed.containerEnv()).isEmpty();
        assertThat(seed.memoryLimit()).isNull();
        assertThat(seed.cpuLimit()).isNull();
    }

    @Test
    void parseYaml_withEnvAndEndpoints() {
        Optional<DevfileSeed> result = parser.parseYaml("""
                schemaVersion: "2.2.0"
                metadata:
                  name: backend
                components:
                  - name: runtime
                    container:
                      image: maven:3.9-eclipse-temurin-21
                      env:
                        - name: MAVEN_OPTS
                          value: "-Xmx512m"
                        - name: DEBUG
                          value: "true"
                      endpoints:
                        - name: http
                          targetPort: 8080
                        - name: debug
                          targetPort: 5005
                """);

        assertThat(result).isPresent();
        DevfileSeed seed = result.get();
        assertThat(seed.image()).isEqualTo("maven:3.9-eclipse-temurin-21");
        assertThat(seed.containerEnv())
            .containsEntry("MAVEN_OPTS", "-Xmx512m")
            .containsEntry("DEBUG", "true");
        assertThat(seed.forwardPorts()).containsExactly("8080", "5005");
    }

    @Test
    void parseYaml_withResourceLimits() {
        Optional<DevfileSeed> result = parser.parseYaml("""
                schemaVersion: "2.3.0"
                metadata:
                  name: heavy-build
                components:
                  - name: builder
                    container:
                      image: node:20
                      memoryLimit: 2Gi
                      memoryRequest: 512Mi
                      cpuLimit: "2"
                      cpuRequest: 500m
                """);

        assertThat(result).isPresent();
        DevfileSeed seed = result.get();
        assertThat(seed.memoryLimit()).isEqualTo("2Gi");
        assertThat(seed.memoryRequest()).isEqualTo("512Mi");
        assertThat(seed.cpuLimit()).isEqualTo("2");
        assertThat(seed.cpuRequest()).isEqualTo("500m");
    }

    @Test
    void parseYaml_picksFirstContainerComponent() {
        Optional<DevfileSeed> result = parser.parseYaml("""
                schemaVersion: "2.2.0"
                metadata:
                  name: multi
                components:
                  - name: primary
                    container:
                      image: python:3.12
                  - name: secondary
                    container:
                      image: postgres:15
                """);

        assertThat(result).isPresent();
        assertThat(result.get().image()).isEqualTo("python:3.12");
        assertThat(result.get().componentName()).isEqualTo("primary");
    }

    @Test
    void parseYaml_skipsNonContainerComponents() {
        Optional<DevfileSeed> result = parser.parseYaml("""
                schemaVersion: "2.2.0"
                metadata:
                  name: mixed
                components:
                  - name: k8s-thing
                    kubernetes:
                      uri: deploy.yaml
                  - name: dev
                    container:
                      image: golang:1.22
                """);

        assertThat(result).isPresent();
        assertThat(result.get().image()).isEqualTo("golang:1.22");
        assertThat(result.get().componentName()).isEqualTo("dev");
    }

    @Test
    void parseYaml_metadataName_nullWhenAbsent() {
        Optional<DevfileSeed> result = parser.parseYaml("""
                schemaVersion: "2.2.0"
                components:
                  - name: dev
                    container:
                      image: alpine:3.19
                """);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isNull();
        assertThat(result.get().image()).isEqualTo("alpine:3.19");
    }

    // --- parseYaml: failure / edge cases ----------------------------------------------------

    @Test
    void parseYaml_returnsEmpty_whenContentBlank() {
        assertThat(parser.parseYaml("")).isEmpty();
        assertThat(parser.parseYaml("  ")).isEmpty();
        assertThat(parser.parseYaml(null)).isEmpty();
    }

    @Test
    void parseYaml_returnsEmpty_whenMissingSchemaVersion() {
        Optional<DevfileSeed> result = parser.parseYaml("""
                metadata:
                  name: no-version
                components:
                  - name: dev
                    container:
                      image: ubuntu:22.04
                """);

        assertThat(result).isEmpty();
    }

    @Test
    void parseYaml_returnsEmpty_whenUnsupportedSchemaVersion() {
        Optional<DevfileSeed> result = parser.parseYaml("""
                schemaVersion: "1.0.0"
                components:
                  - name: dev
                    container:
                      image: ubuntu:22.04
                """);

        assertThat(result).isEmpty();
    }

    @Test
    void parseYaml_returnsEmpty_whenNoContainerComponents() {
        Optional<DevfileSeed> result = parser.parseYaml("""
                schemaVersion: "2.2.0"
                metadata:
                  name: no-container
                components:
                  - name: k8s
                    kubernetes:
                      uri: deploy.yaml
                """);

        assertThat(result).isEmpty();
    }

    @Test
    void parseYaml_returnsEmpty_whenComponentsAbsent() {
        Optional<DevfileSeed> result = parser.parseYaml("""
                schemaVersion: "2.2.0"
                metadata:
                  name: empty
                """);

        assertThat(result).isEmpty();
    }

    // --- discover (file-system) -------------------------------------------------------------

    @Test
    void discover_findsDevfileYaml(@TempDir Path repo) throws IOException {
        Files.writeString(repo.resolve("devfile.yaml"), """
                schemaVersion: "2.2.0"
                metadata:
                  name: discovered
                components:
                  - name: dev
                    container:
                      image: eclipse/che-theia:next
                """);

        Optional<DevfileSeed> result = parser.discover(repo);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("discovered");
    }

    @Test
    void discover_findsDevfileYml(@TempDir Path repo) throws IOException {
        Files.writeString(repo.resolve("devfile.yml"), """
                schemaVersion: "2.2.0"
                metadata:
                  name: yml-found
                components:
                  - name: dev
                    container:
                      image: alpine:3.19
                """);

        Optional<DevfileSeed> result = parser.discover(repo);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("yml-found");
    }

    @Test
    void discover_prefersDevfileYamlOverYml(@TempDir Path repo) throws IOException {
        Files.writeString(repo.resolve("devfile.yaml"), """
                schemaVersion: "2.2.0"
                metadata:
                  name: yaml-wins
                components:
                  - name: dev
                    container:
                      image: image-from-yaml
                """);
        Files.writeString(repo.resolve("devfile.yml"), """
                schemaVersion: "2.2.0"
                metadata:
                  name: yml-loses
                components:
                  - name: dev
                    container:
                      image: image-from-yml
                """);

        Optional<DevfileSeed> result = parser.discover(repo);

        assertThat(result).isPresent();
        assertThat(result.get().image()).isEqualTo("image-from-yaml");
    }

    @Test
    void discover_returnsEmpty_whenNoDevfilePresent(@TempDir Path repo) throws IOException {
        Optional<DevfileSeed> result = parser.discover(repo);
        assertThat(result).isEmpty();
    }

    @Test
    void parse_throwsDevfileParseException_whenNoContainerComponent(@TempDir Path repo) throws IOException {
        Path devfile = repo.resolve("devfile.yaml");
        Files.writeString(devfile, """
                schemaVersion: "2.2.0"
                metadata:
                  name: no-container
                components:
                  - name: k8s
                    kubernetes:
                      uri: deploy.yaml
                """);

        assertThatThrownBy(() -> parser.parse(devfile))
            .isInstanceOf(DevfileParseException.class)
            .hasMessageContaining("No parseable container component");
    }
}
