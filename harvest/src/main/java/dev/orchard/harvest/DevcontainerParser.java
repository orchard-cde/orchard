package dev.orchard.harvest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orchard.core.model.Seed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Parses .devcontainer/devcontainer.json files into Seed objects.
 * Supports the devcontainer specification: https://containers.dev/implementors/json_reference/
 */
public class DevcontainerParser {

    private static final Logger log = LoggerFactory.getLogger(DevcontainerParser.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Discovers and parses devcontainer.json from a repository root.
     * Looks in standard locations: .devcontainer/devcontainer.json, .devcontainer.json
     */
    public Optional<Seed> discover(Path repositoryRoot) throws IOException {
        Path[] searchPaths = {
            repositoryRoot.resolve(".devcontainer/devcontainer.json"),
            repositoryRoot.resolve(".devcontainer.json")
        };

        for (Path path : searchPaths) {
            if (Files.exists(path)) {
                log.info("Found devcontainer at: {}", path);
                return Optional.of(parse(path));
            }
        }

        log.info("No devcontainer.json found in {}", repositoryRoot);
        return Optional.empty();
    }

    /**
     * Parses a devcontainer.json file into a Seed.
     */
    public Seed parse(Path devcontainerPath) throws IOException {
        String content = Files.readString(devcontainerPath);
        return parseJson(content)
            .orElseThrow(() -> new IOException("Failed to parse devcontainer.json at " + devcontainerPath));
    }

    /**
     * Parses devcontainer.json content from a raw JSON string into a Seed.
     * Returns Optional.empty() if the content is null, blank, or fails to parse.
     */
    public Optional<Seed> parseJson(String jsonContent) {
        if (jsonContent == null || jsonContent.isBlank()) {
            log.warn("Cannot parse devcontainer.json: content is null or blank");
            return Optional.empty();
        }

        try {
            return Optional.of(doParse(jsonContent));
        } catch (IOException e) {
            log.error("Failed to parse devcontainer.json content: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    private Seed doParse(String jsonContent) throws IOException {
        JsonNode root = objectMapper.readTree(jsonContent);
        Seed.Builder builder = Seed.builder();

        // Name
        if (root.has("name")) {
            builder.name(root.get("name").asText());
        }

        // Image
        if (root.has("image")) {
            builder.image(root.get("image").asText());
        }

        // Dockerfile
        if (root.has("dockerFile") || root.has("build")) {
            JsonNode build = root.has("build") ? root.get("build") : root;
            if (build.has("dockerfile")) {
                builder.dockerfilePath(build.get("dockerfile").asText());
            } else if (build.has("dockerFile")) {
                builder.dockerfilePath(build.get("dockerFile").asText());
            }

            // Build args
            if (build.has("args")) {
                builder.buildArgs(parseStringMap(build.get("args")));
            }
        }

        // Docker Compose
        if (root.has("dockerComposeFile")) {
            JsonNode dcf = root.get("dockerComposeFile");
            if (dcf.isArray() && !dcf.isEmpty()) {
                builder.dockerComposeFile(dcf.get(0).asText());
            } else if (dcf.isTextual()) {
                builder.dockerComposeFile(dcf.asText());
            }
        }

        // Service (which compose service is the primary dev container)
        if (root.has("service")) {
            builder.service(root.get("service").asText());
        }

        // Features
        if (root.has("features")) {
            List<String> features = new ArrayList<>();
            root.get("features").fieldNames().forEachRemaining(features::add);
            builder.features(features);
        }

        // Forward ports
        if (root.has("forwardPorts")) {
            List<String> ports = new ArrayList<>();
            root.get("forwardPorts").forEach(node -> ports.add(node.asText()));
            builder.forwardPorts(ports);
        }

        // Container env
        if (root.has("containerEnv")) {
            builder.containerEnv(parseStringMap(root.get("containerEnv")));
        }

        // Lifecycle commands
        if (root.has("postCreateCommand")) {
            builder.postCreateCommands(parseCommand(root.get("postCreateCommand")));
        }

        if (root.has("postStartCommand")) {
            builder.postStartCommands(parseCommand(root.get("postStartCommand")));
        }

        // VS Code customizations
        if (root.has("customizations") && root.get("customizations").has("vscode")) {
            JsonNode vscode = root.get("customizations").get("vscode");
            List<String> extensions = new ArrayList<>();
            Map<String, Object> settings = new HashMap<>();

            if (vscode.has("extensions")) {
                vscode.get("extensions").forEach(ext -> extensions.add(ext.asText()));
            }

            if (vscode.has("settings")) {
                settings = objectMapper.convertValue(vscode.get("settings"), Map.class);
            }

            builder.vscodeCustomizations(new Seed.VsCodeCustomizations(extensions, settings));
        }

        return builder.build();
    }

    private Map<String, String> parseStringMap(JsonNode node) {
        Map<String, String> map = new HashMap<>();
        node.fields().forEachRemaining(entry ->
            map.put(entry.getKey(), entry.getValue().asText()));
        return map;
    }

    private List<String> parseCommand(JsonNode node) {
        if (node.isTextual()) {
            return List.of(node.asText());
        } else if (node.isArray()) {
            List<String> commands = new ArrayList<>();
            node.forEach(cmd -> commands.add(cmd.asText()));
            return commands;
        }
        return List.of();
    }
}
