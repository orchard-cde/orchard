package dev.orchard.harvest;

import dev.orchard.core.model.DevcontainerSeed;
import dev.orchard.core.model.LifecycleCommand;
import dev.orchard.core.model.Seed;
import dev.orchard.core.model.WaitFor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Parses .devcontainer/devcontainer.json files into Seed objects.
 * Supports the devcontainer specification: https://containers.dev/implementors/json_reference/
 */
public class DevcontainerParser {

    private static final Logger log = LoggerFactory.getLogger(DevcontainerParser.class);
    private static final ObjectMapper objectMapper = LenientBinding.configure(JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA), LenientBinding.Mode.STRICT)
        .build();

    /**
     * Discovers and parses devcontainer.json from a repository root.
     * Looks in standard locations: .devcontainer/devcontainer.json, .devcontainer.json
     */
    public Optional<DevcontainerSeed> discover(Path repositoryRoot) throws IOException {
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

        // Fall back to first alphabetical subfolder config
        Path devcontainerDir = repositoryRoot.resolve(".devcontainer");
        if (Files.isDirectory(devcontainerDir)) {
            try (Stream<Path> entries = Files.list(devcontainerDir)) {
                Optional<Path> subfolderConfig = entries
                    .filter(Files::isDirectory)
                    .filter(p -> !"default".equals(p.getFileName().toString()))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(dir -> dir.resolve("devcontainer.json"))
                    .filter(Files::exists)
                    .findFirst();

                if (subfolderConfig.isPresent()) {
                    log.info("Found devcontainer at: {}", subfolderConfig.get());
                    return Optional.of(parse(subfolderConfig.get()));
                }
            }
        }

        log.info("No devcontainer.json found in {}", repositoryRoot);
        return Optional.empty();
    }

    /**
     * Discovers all devcontainer.json configurations in a repository.
     * Returns a LinkedHashMap keyed by config name, with "default" first (if present),
     * followed by subfolder configs sorted alphabetically by folder name.
     *
     * <ul>
     *   <li>Root config (.devcontainer/devcontainer.json or .devcontainer.json) → key "default"</li>
     *   <li>Subfolder configs (.devcontainer/&lt;folder&gt;/devcontainer.json) → key is folder name</li>
     *   <li>Folders named "default" are excluded to avoid key collision</li>
     * </ul>
     *
     * @throws IOException if any discovered config file cannot be parsed
     */
    public Map<String, DevcontainerSeed> discoverAll(Path repositoryRoot) throws IOException {
        Map<String, DevcontainerSeed> result = new LinkedHashMap<>();

        // Root config — higher-priority location wins
        Path[] rootPaths = {
            repositoryRoot.resolve(".devcontainer/devcontainer.json"),
            repositoryRoot.resolve(".devcontainer.json")
        };
        for (Path path : rootPaths) {
            if (Files.exists(path)) {
                result.put("default", parse(path));
                break;
            }
        }

        // Subfolder configs — one level deep, alphabetical, exclude "default"
        Path devcontainerDir = repositoryRoot.resolve(".devcontainer");
        if (Files.isDirectory(devcontainerDir)) {
            List<Path> subfolderConfigs;
            try (Stream<Path> entries = Files.list(devcontainerDir)) {
                subfolderConfigs = entries
                    .filter(Files::isDirectory)
                    .filter(p -> !"default".equals(p.getFileName().toString()))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(dir -> dir.resolve("devcontainer.json"))
                    .filter(Files::exists)
                    .collect(Collectors.toList());
            }
            for (Path configPath : subfolderConfigs) {
                String name = configPath.getParent().getFileName().toString();
                result.put(name, parse(configPath));
            }
        }

        return result;
    }

    /**
     * Parses a devcontainer.json file into a DevcontainerSeed.
     */
    public DevcontainerSeed parse(Path devcontainerPath) throws IOException {
        String content = Files.readString(devcontainerPath);
        return parseJson(content)
            .orElseThrow(() -> new IOException("Failed to parse devcontainer.json at " + devcontainerPath));
    }

    /**
     * Parses devcontainer.json content from a raw JSON string into a DevcontainerSeed.
     * Returns Optional.empty() if the content is null, blank, or fails to parse.
     */
    public Optional<DevcontainerSeed> parseJson(String jsonContent) {
        if (jsonContent == null || jsonContent.isBlank()) {
            log.warn("Cannot parse devcontainer.json: content is null or blank");
            return Optional.empty();
        }

        try {
            return Optional.of(doParse(jsonContent));
        } catch (JacksonException e) {
            log.error("Failed to parse devcontainer.json content: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    private DevcontainerSeed doParse(String jsonContent) {
        DevcontainerJson json = objectMapper.readValue(jsonContent, DevcontainerJson.class);
        if (json == null) {
            json = objectMapper.readValue("{}", DevcontainerJson.class);
        }
        DevcontainerSeed.Builder builder = DevcontainerSeed.devcontainer();

        ifSet(json.name(), builder::name);
        ifSet(json.image(), builder::image);

        // spec allows top-level dockerFile or a build object (issue #31)
        DevcontainerJson.Build build = json.build() != null || json.dockerFile() == null
            ? json.build()
            : objectMapper.readValue(jsonContent, DevcontainerJson.Build.class);
        if (build != null) {
            // dockerfile path — spec spells it "dockerfile" inside build, "dockerFile" at root
            ifSet(build.dockerfile() != null ? build.dockerfile() : build.dockerFile(), builder::dockerfilePath);
            ifSet(build.context(), builder::buildContext);
            ifSet(build.target(), builder::buildTarget);
            ifSet(build.cacheFrom(), builder::buildCacheFrom);
            ifSet(build.options(), builder::buildOptions);
            ifSet(build.args(), builder::buildArgs);
        }

        ifSet(json.dockerComposeFile(), builder::dockerComposeFiles);
        ifSet(json.service(), builder::service);
        ifSet(json.runServices(), builder::runServices);
        if (json.features() != null) {
            Map<String, Map<String, Object>> features = new LinkedHashMap<>();
            json.features().forEach((id, options) -> features.put(id, options == null ? Map.of() : options));
            builder.features(features);
        }
        ifSet(json.overrideFeatureInstallOrder(), builder::overrideFeatureInstallOrder);
        ifSet(json.forwardPorts(), builder::forwardPorts);
        ifSet(json.containerEnv(), builder::containerEnv);
        ifSet(json.remoteEnv(), builder::remoteEnv);
        ifSet(json.remoteUser(), builder::remoteUser);
        ifSet(json.containerUser(), builder::containerUser);
        ifSet(json.mounts(), builder::mounts);
        ifSet(json.runArgs(), builder::runArgs);
        ifSet(json.workspaceFolder(), builder::workspaceFolder);
        ifSet(json.workspaceMount(), builder::workspaceMount);
        ifSet(json.privileged(), builder::privileged);
        ifSet(json.init(), builder::init);
        ifSet(json.overrideCommand(), builder::overrideCommand);
        ifSet(json.updateRemoteUserUID(), builder::updateRemoteUserUID);
        ifSet(json.capAdd(), builder::capAdd);
        ifSet(json.securityOpt(), builder::securityOpt);
        ifSet(json.shutdownAction(), builder::shutdownAction);

        // portsAttributes / otherPortsAttributes (issue #101)
        if (json.portsAttributes() != null) {
            Map<String, DevcontainerSeed.PortAttributes> ports = new LinkedHashMap<>();
            json.portsAttributes().forEach((port, attrs) ->
                ports.put(port, attrs != null ? attrs : new DevcontainerSeed.PortAttributes(null, null, null, null, null)));
            builder.portsAttributes(ports);
        }
        ifSet(json.otherPortsAttributes(), builder::otherPortsAttributes);

        // userEnvProbe (issue #102)
        if (json.userEnvProbe() != null) {
            DevcontainerSeed.UserEnvProbe probe = DevcontainerSeed.UserEnvProbe.fromSpec(json.userEnvProbe());
            if (probe != null) {
                builder.userEnvProbe(probe);
            } else {
                log.warn("Unknown userEnvProbe value '{}', ignoring", json.userEnvProbe());
            }
        }

        // hostRequirements (issue #103)
        if (json.hostRequirements() != null) {
            DevcontainerJson.HostRequirements host = json.hostRequirements();
            Object gpu = host.gpu() instanceof Boolean || host.gpu() instanceof String || host.gpu() instanceof Map
                ? host.gpu() : null;
            builder.hostRequirements(new DevcontainerSeed.HostRequirements(host.cpus(), host.memory(), host.storage(), gpu));
        }

        // secrets (issue #104) — names only, no values
        if (json.secrets() != null) {
            Map<String, DevcontainerSeed.SecretDeclaration> secrets = new LinkedHashMap<>();
            json.secrets().forEach((secret, declaration) ->
                secrets.put(secret, declaration != null ? declaration : new DevcontainerSeed.SecretDeclaration(null, null)));
            builder.secrets(secrets);
        }

        ifSet(json.initializeCommand(), builder::initializeCommand);
        ifSet(json.onCreateCommand(), builder::onCreateCommand);
        ifSet(json.updateContentCommand(), builder::updateContentCommand);
        ifSet(json.postCreateCommand(), builder::postCreateCommand);
        ifSet(json.postStartCommand(), builder::postStartCommand);
        ifSet(json.postAttachCommand(), builder::postAttachCommand);

        if (json.waitFor() != null) {
            builder.waitFor(parseWaitFor(json.waitFor()));
        }

        if (json.customizations() != null && json.customizations().vscode() != null) {
            DevcontainerJson.VsCode vscode = json.customizations().vscode();
            builder.vscodeCustomizations(new DevcontainerSeed.VsCodeCustomizations(
                vscode.extensions() != null ? vscode.extensions() : List.of(),
                vscode.settings()));
        }

        return builder.build();
    }

    private static <T> void ifSet(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    private WaitFor parseWaitFor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.toLowerCase()) {
            case "initializecommand"    -> WaitFor.INITIALIZE_COMMAND;
            case "oncreatecommand"      -> WaitFor.ON_CREATE_COMMAND;
            case "updatecontentcommand" -> WaitFor.UPDATE_CONTENT_COMMAND;
            case "postattachcommand"    -> WaitFor.POST_ATTACH_COMMAND;
            default -> {
                log.warn("Unknown waitFor value '{}', ignoring", value);
                yield null;
            }
        };
    }
}
