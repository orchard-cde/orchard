package dev.orchard.harvest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;
import dev.orchard.core.model.DevfileSeed;
import dev.orchard.core.model.Seed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Parses {@code devfile.yaml} files into {@link DevfileSeed} objects.
 *
 * <p>Supports devfile schema versions 2.x. Only {@code container} components are mapped in
 * this slice; {@code kubernetes} / {@code openshift} components are tracked separately (#86).
 *
 * <p>The first {@code container} component (in document order) is treated as the primary
 * development container. Its fields map to the common {@link DevfileSeed} fields that drive
 * the VM + SSH provisioning path.
 *
 * <p>Discovery search order (all relative to the repository root):
 * <ol>
 *   <li>{@code devfile.yaml}</li>
 *   <li>{@code devfile.yml}</li>
 *   <li>{@code .devfile.yaml}</li>
 *   <li>{@code .devfile.yml}</li>
 * </ol>
 */
public class DevfileParser {

    private static final Logger log = LoggerFactory.getLogger(DevfileParser.class);

    /** Only devfile 2.x is supported in this slice. */
    private static final String SUPPORTED_SCHEMA_PREFIX = "2.";

    private static final ObjectMapper yamlMapper = YAMLMapper.builder().build();

    /**
     * Discovers and parses a devfile from a repository root.
     * Returns {@link Optional#empty()} when no devfile is present.
     */
    public Optional<DevfileSeed> discover(Path repositoryRoot) throws IOException {
        String[] candidates = {"devfile.yaml", "devfile.yml", ".devfile.yaml", ".devfile.yml"};
        for (String filename : candidates) {
            Path candidate = repositoryRoot.resolve(filename);
            if (Files.exists(candidate)) {
                log.info("Found devfile at: {}", candidate);
                return Optional.of(parse(candidate));
            }
        }
        log.debug("No devfile found in {}", repositoryRoot);
        return Optional.empty();
    }

    /**
     * Parses a devfile at the given path.
     *
     * @throws IOException if the file cannot be read or contains invalid YAML
     * @throws DevfileParseException if the file is not a supported devfile
     */
    public DevfileSeed parse(Path devfilePath) throws IOException {
        String content = Files.readString(devfilePath);
        return parseYaml(content)
            .orElseThrow(() -> new DevfileParseException(
                "No parseable container component found in devfile at " + devfilePath));
    }

    /**
     * Parses devfile YAML from a raw string. Returns {@link Optional#empty()} if the
     * content is null, blank, or fails to parse.
     */
    public Optional<DevfileSeed> parseYaml(String yamlContent) {
        if (yamlContent == null || yamlContent.isBlank()) {
            log.warn("Cannot parse devfile: content is null or blank");
            return Optional.empty();
        }
        try {
            return doParse(yamlContent);
        } catch (DevfileParseException e) {
            log.error("Failed to parse devfile: {}", e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to parse devfile YAML: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    private Optional<DevfileSeed> doParse(String yamlContent) {
        JsonNode root = yamlMapper.readTree(yamlContent);

        // schemaVersion is required; only 2.x is supported in this slice
        String schemaVersion = root.path("schemaVersion").asText(null);
        if (schemaVersion == null || schemaVersion.isBlank()) {
            throw new DevfileParseException("Missing schemaVersion — not a valid devfile");
        }
        if (!schemaVersion.startsWith(SUPPORTED_SCHEMA_PREFIX)) {
            throw new DevfileParseException(
                "Unsupported devfile schemaVersion '%s'; only 2.x is supported".formatted(schemaVersion));
        }

        // Workspace name from metadata
        String workspaceName = root.path("metadata").path("name").asText(null);

        // Find the first container component
        JsonNode components = root.path("components");
        if (!components.isArray() || components.isEmpty()) {
            log.debug("Devfile has no components array");
            return Optional.empty();
        }

        for (JsonNode component : components) {
            JsonNode container = component.path("container");
            if (container.isMissingNode()) {
                continue; // skip kubernetes/openshift/image/volume components
            }
            String componentName = component.path("name").asText(null);
            return Optional.of(mapContainerComponent(workspaceName, componentName, container));
        }

        log.debug("Devfile has no container components");
        return Optional.empty();
    }

    /**
     * Maps a devfile {@code container} component node to a {@link DevfileSeed}.
     *
     * <p>Fields mapped from the component:
     * <ul>
     *   <li>{@code image} → {@link DevfileSeed#image()}</li>
     *   <li>component {@code name} → {@link DevfileSeed#componentName()}</li>
     *   <li>{@code env[].name/value} → {@link DevfileSeed#containerEnv()}</li>
     *   <li>{@code endpoints[].targetPort} → {@link DevfileSeed#forwardPorts()}</li>
     *   <li>{@code memoryLimit} → {@link DevfileSeed#memoryLimit()}</li>
     *   <li>{@code memoryRequest} → {@link DevfileSeed#memoryRequest()}</li>
     *   <li>{@code cpuLimit} → {@link DevfileSeed#cpuLimit()}</li>
     *   <li>{@code cpuRequest} → {@link DevfileSeed#cpuRequest()}</li>
     * </ul>
     * The workspace {@code metadata.name} becomes {@link DevfileSeed#name()}.
     */
    private DevfileSeed mapContainerComponent(String workspaceName, String componentName, JsonNode container) {
        DevfileSeed.Builder builder = Seed.devfile();

        if (workspaceName != null && !workspaceName.isBlank()) {
            builder.name(workspaceName);
        }
        if (componentName != null && !componentName.isBlank()) {
            builder.componentName(componentName);
        }

        String image = container.path("image").asText(null);
        if (image != null && !image.isBlank()) {
            builder.image(image);
        }

        // env: [{name: X, value: Y}, ...]
        JsonNode envArray = container.path("env");
        if (envArray.isArray() && !envArray.isEmpty()) {
            Map<String, String> env = new LinkedHashMap<>();
            for (JsonNode entry : envArray) {
                String envName = entry.path("name").asText(null);
                if (envName != null && !envName.isBlank()) {
                    env.put(envName, entry.path("value").asText(""));
                }
            }
            if (!env.isEmpty()) {
                builder.containerEnv(env);
            }
        }

        // endpoints → forwardPorts via targetPort
        JsonNode endpoints = container.path("endpoints");
        if (endpoints.isArray() && !endpoints.isEmpty()) {
            List<String> ports = new ArrayList<>();
            for (JsonNode endpoint : endpoints) {
                int targetPort = endpoint.path("targetPort").asInt(0);
                if (targetPort > 0) {
                    ports.add(String.valueOf(targetPort));
                }
            }
            if (!ports.isEmpty()) {
                builder.forwardPorts(ports);
            }
        }

        // Resource limits / requests — all optional
        nullableText(container, "memoryLimit").ifPresent(builder::memoryLimit);
        nullableText(container, "memoryRequest").ifPresent(builder::memoryRequest);
        nullableText(container, "cpuLimit").ifPresent(builder::cpuLimit);
        nullableText(container, "cpuRequest").ifPresent(builder::cpuRequest);

        return builder.build();
    }

    private static Optional<String> nullableText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return (value != null && !value.isBlank()) ? Optional.of(value) : Optional.empty();
    }

    /**
     * Thrown when a devfile cannot be parsed due to a structural problem —
     * unsupported schema version, missing required fields, etc.
     */
    public static class DevfileParseException extends RuntimeException {
        public DevfileParseException(String message) {
            super(message);
        }
    }
}
