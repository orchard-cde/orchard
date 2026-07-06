package dev.orchard.api.service;

import dev.orchard.core.model.DevcontainerSeed;
import dev.orchard.core.model.DevfileSeed;
import dev.orchard.core.model.Seed;
import dev.orchard.harvest.DevcontainerParser;
import dev.orchard.harvest.DevfileParser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** Precedence rules in {@link GroveService#resolveSeed} for each {@link SeedSpec} (issue #129). */
class GroveServiceSeedResolutionTest {

    private static final DevcontainerParser DEVCONTAINER_PARSER = new DevcontainerParser();
    private static final DevfileParser DEVFILE_PARSER = new DevfileParser();

    private static final String DEVCONTAINER_PATH = "/workspace/.devcontainer/devcontainer.json";
    private static final String DEVFILE_PATH = "/workspace/devfile.yaml";

    private static final String DEVCONTAINER_JSON = "{\"image\": \"ubuntu:22.04\"}";
    private static final String DEVFILE_YAML = """
            schemaVersion: "2.2.0"
            metadata:
              name: my-workspace
            components:
              - name: dev
                container:
                  image: quay.io/devfile/universal-developer-image:latest
            """;

    private static final String DEFAULT_IMAGE = "mcr.microsoft.com/devcontainers/base:ubuntu";

    private static Seed resolve(SeedSpec spec, Map<String, String> files) {
        Function<String, Optional<String>> reader = path -> Optional.ofNullable(files.get(path));
        return GroveService.resolveSeed(spec, reader, DEVCONTAINER_PARSER, DEVFILE_PARSER);
    }

    @Test
    void auto_prefersDevcontainerWhenBothPresent() {
        Seed seed = resolve(SeedSpec.AUTO, Map.of(
            DEVCONTAINER_PATH, DEVCONTAINER_JSON,
            DEVFILE_PATH, DEVFILE_YAML));
        assertThat(seed).isInstanceOf(DevcontainerSeed.class);
        assertThat(seed.image()).isEqualTo("ubuntu:22.04");
    }

    @Test
    void auto_fallsBackToDevfileWhenNoDevcontainer() {
        Seed seed = resolve(SeedSpec.AUTO, Map.of(DEVFILE_PATH, DEVFILE_YAML));
        assertThat(seed).isInstanceOf(DevfileSeed.class);
        assertThat(seed.image()).isEqualTo("quay.io/devfile/universal-developer-image:latest");
    }

    @Test
    void auto_defaultsToDevcontainerWhenNeitherPresent() {
        Seed seed = resolve(SeedSpec.AUTO, Map.of());
        assertThat(seed).isInstanceOf(DevcontainerSeed.class);
        assertThat(seed.image()).isEqualTo(DEFAULT_IMAGE);
    }

    @Test
    void devcontainer_usesDevcontainerWhenPresent() {
        Seed seed = resolve(SeedSpec.DEVCONTAINER, Map.of(
            DEVCONTAINER_PATH, DEVCONTAINER_JSON,
            DEVFILE_PATH, DEVFILE_YAML));
        assertThat(seed).isInstanceOf(DevcontainerSeed.class);
        assertThat(seed.image()).isEqualTo("ubuntu:22.04");
    }

    @Test
    void devcontainer_ignoresDevfileAndSynthesizesDefaultWhenAbsent() {
        Seed seed = resolve(SeedSpec.DEVCONTAINER, Map.of(DEVFILE_PATH, DEVFILE_YAML));
        assertThat(seed).isInstanceOf(DevcontainerSeed.class);
        assertThat(seed.image()).isEqualTo(DEFAULT_IMAGE);
    }

    @Test
    void devfile_usesDevfileWhenPresent() {
        Seed seed = resolve(SeedSpec.DEVFILE, Map.of(
            DEVCONTAINER_PATH, DEVCONTAINER_JSON,
            DEVFILE_PATH, DEVFILE_YAML));
        assertThat(seed).isInstanceOf(DevfileSeed.class);
        assertThat(seed.image()).isEqualTo("quay.io/devfile/universal-developer-image:latest");
    }

    @Test
    void devfile_ignoresDevcontainerAndSynthesizesDefaultWhenAbsent() {
        Seed seed = resolve(SeedSpec.DEVFILE, Map.of(DEVCONTAINER_PATH, DEVCONTAINER_JSON));
        assertThat(seed).isInstanceOf(DevfileSeed.class);
        assertThat(seed.image()).isEqualTo(DEFAULT_IMAGE);
    }
}
