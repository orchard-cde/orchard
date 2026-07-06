package dev.orchard.harvest;

import dev.orchard.core.model.DevcontainerSeed;
import dev.orchard.core.model.LifecycleCommand;
import dev.orchard.core.model.Seed;
import dev.orchard.core.model.WaitFor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Parses .devcontainer/devcontainer.json files into Seed objects.
 * Supports the devcontainer specification: https://containers.dev/implementors/json_reference/
 */
public class DevcontainerParser {

    private static final Logger log = LoggerFactory.getLogger(DevcontainerParser.class);
    private static final ObjectMapper objectMapper = JsonMapper.builder()
        .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
        .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
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
        JsonNode root = objectMapper.readTree(jsonContent);
        DevcontainerSeed.Builder builder = DevcontainerSeed.devcontainer();

        // Name
        if (root.has("name")) {
            builder.name(root.get("name").asText());
        }

        // Image
        if (root.has("image")) {
            builder.image(root.get("image").asText());
        }

        // build.* fields — spec allows top-level dockerFile or a build object (issue #31)
        if (root.has("dockerFile") || root.has("build")) {
            JsonNode build = root.has("build") ? root.get("build") : root;

            // dockerfile path — spec spells it "dockerfile" inside build, "dockerFile" at root
            if (build.has("dockerfile")) {
                builder.dockerfilePath(build.get("dockerfile").asText());
            } else if (build.has("dockerFile")) {
                builder.dockerfilePath(build.get("dockerFile").asText());
            }
            if (build.has("context")) {
                builder.buildContext(build.get("context").asText());
            }
            if (build.has("target")) {
                builder.buildTarget(build.get("target").asText());
            }
            // cacheFrom: string or array
            if (build.has("cacheFrom")) {
                builder.buildCacheFrom(parseStringList(build.get("cacheFrom")));
            }
            // options: array of strings
            if (build.has("options")) {
                List<String> opts = new ArrayList<>();
                build.get("options").forEach(n -> opts.add(n.asText()));
                builder.buildOptions(opts);
            }
            if (build.has("args")) {
                builder.buildArgs(parseStringMap(build.get("args")));
            }
        }

        // dockerComposeFile: string or array — store all files (issue #32)
        if (root.has("dockerComposeFile")) {
            builder.dockerComposeFiles(parseStringList(root.get("dockerComposeFile")));
        }

        // service (compose primary service)
        if (root.has("service")) {
            builder.service(root.get("service").asText());
        }

        // runServices (issue #32)
        if (root.has("runServices")) {
            List<String> svc = new ArrayList<>();
            root.get("runServices").forEach(n -> svc.add(n.asText()));
            builder.runServices(svc);
        }

        // features
        if (root.has("features")) {
            Map<String, Map<String, Object>> features = new LinkedHashMap<>();
            root.get("features").properties().forEach(entry -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> options = objectMapper.convertValue(entry.getValue(), Map.class);
                features.put(entry.getKey(), options == null ? Map.of() : options);
            });
            builder.features(features);
        }

        // overrideFeatureInstallOrder (issue #105)
        if (root.has("overrideFeatureInstallOrder")) {
            List<String> order = new ArrayList<>();
            root.get("overrideFeatureInstallOrder").forEach(n -> order.add(n.asText()));
            builder.overrideFeatureInstallOrder(order);
        }

        // forwardPorts
        if (root.has("forwardPorts")) {
            List<String> ports = new ArrayList<>();
            root.get("forwardPorts").forEach(node -> ports.add(node.asText()));
            builder.forwardPorts(ports);
        }

        // containerEnv
        if (root.has("containerEnv")) {
            builder.containerEnv(parseStringMap(root.get("containerEnv")));
        }

        // remoteEnv (issue #29)
        if (root.has("remoteEnv")) {
            builder.remoteEnv(parseStringMap(root.get("remoteEnv")));
        }

        // remoteUser / containerUser (issue #29)
        if (root.has("remoteUser")) {
            builder.remoteUser(root.get("remoteUser").asText());
        }
        if (root.has("containerUser")) {
            builder.containerUser(root.get("containerUser").asText());
        }

        // mounts — string or object; store as rendered strings (issue #29)
        if (root.has("mounts")) {
            List<String> mountList = new ArrayList<>();
            root.get("mounts").forEach(m -> {
                if (m.isTextual()) {
                    mountList.add(m.asText());
                } else {
                    // object form: render back to "key=value,..." string matching docker --mount syntax
                    StringBuilder sb = new StringBuilder();
                    m.properties().forEach(e -> {
                        if (!sb.isEmpty()) {
                            sb.append(",");
                        }
                        sb.append(e.getKey()).append("=").append(e.getValue().asText());
                    });
                    mountList.add(sb.toString());
                }
            });
            builder.mounts(mountList);
        }

        // runArgs (issue #29)
        if (root.has("runArgs")) {
            List<String> args = new ArrayList<>();
            root.get("runArgs").forEach(n -> args.add(n.asText()));
            builder.runArgs(args);
        }

        // workspaceFolder / workspaceMount (issue #29)
        if (root.has("workspaceFolder")) {
            builder.workspaceFolder(root.get("workspaceFolder").asText());
        }
        if (root.has("workspaceMount")) {
            builder.workspaceMount(root.get("workspaceMount").asText());
        }

        // privileged / init / overrideCommand / updateRemoteUserUID (issue #29)
        if (root.has("privileged")) {
            builder.privileged(root.get("privileged").asBoolean());
        }
        if (root.has("init")) {
            builder.init(root.get("init").asBoolean());
        }
        if (root.has("overrideCommand")) {
            builder.overrideCommand(root.get("overrideCommand").asBoolean());
        }
        if (root.has("updateRemoteUserUID")) {
            builder.updateRemoteUserUID(root.get("updateRemoteUserUID").asBoolean());
        }

        // capAdd / securityOpt (issue #29)
        if (root.has("capAdd")) {
            List<String> caps = new ArrayList<>();
            root.get("capAdd").forEach(n -> caps.add(n.asText()));
            builder.capAdd(caps);
        }
        if (root.has("securityOpt")) {
            List<String> opts = new ArrayList<>();
            root.get("securityOpt").forEach(n -> opts.add(n.asText()));
            builder.securityOpt(opts);
        }

        // shutdownAction (issues #29/#32)
        if (root.has("shutdownAction")) {
            builder.shutdownAction(root.get("shutdownAction").asText());
        }

        // portsAttributes / otherPortsAttributes (issue #101)
        if (root.has("portsAttributes")) {
            Map<String, DevcontainerSeed.PortAttributes> map = new LinkedHashMap<>();
            root.get("portsAttributes").properties()
                .forEach(e -> map.put(e.getKey(), parsePortAttributes(e.getValue())));
            builder.portsAttributes(map);
        }
        if (root.has("otherPortsAttributes")) {
            builder.otherPortsAttributes(parsePortAttributes(root.get("otherPortsAttributes")));
        }

        // userEnvProbe (issue #102)
        if (root.has("userEnvProbe")) {
            DevcontainerSeed.UserEnvProbe probe =
                DevcontainerSeed.UserEnvProbe.fromSpec(root.get("userEnvProbe").asText());
            if (probe != null) {
                builder.userEnvProbe(probe);
            } else {
                log.warn("Unknown userEnvProbe value '{}', ignoring", root.get("userEnvProbe").asText());
            }
        }

        // hostRequirements (issue #103)
        if (root.has("hostRequirements")) {
            builder.hostRequirements(parseHostRequirements(root.get("hostRequirements")));
        }

        // secrets (issue #104) — names only, no values
        if (root.has("secrets")) {
            Map<String, DevcontainerSeed.SecretDeclaration> secretMap = new LinkedHashMap<>();
            root.get("secrets").properties().forEach(e -> {
                JsonNode v = e.getValue();
                String desc = v.has("description") ? v.get("description").asText() : null;
                String docUrl = v.has("documentationUrl") ? v.get("documentationUrl").asText() : null;
                secretMap.put(e.getKey(), new DevcontainerSeed.SecretDeclaration(desc, docUrl));
            });
            builder.secrets(secretMap);
        }

        // Lifecycle commands
        if (root.has("initializeCommand")) {
            builder.initializeCommand(parseLifecycleCommand(root.get("initializeCommand")));
        }
        if (root.has("onCreateCommand")) {
            builder.onCreateCommand(parseLifecycleCommand(root.get("onCreateCommand")));
        }
        if (root.has("updateContentCommand")) {
            builder.updateContentCommand(parseLifecycleCommand(root.get("updateContentCommand")));
        }
        if (root.has("postCreateCommand")) {
            builder.postCreateCommand(parseLifecycleCommand(root.get("postCreateCommand")));
        }
        if (root.has("postStartCommand")) {
            builder.postStartCommand(parseLifecycleCommand(root.get("postStartCommand")));
        }
        if (root.has("postAttachCommand")) {
            builder.postAttachCommand(parseLifecycleCommand(root.get("postAttachCommand")));
        }

        // waitFor
        if (root.has("waitFor")) {
            builder.waitFor(parseWaitFor(root.get("waitFor").asText()));
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
            builder.vscodeCustomizations(new DevcontainerSeed.VsCodeCustomizations(extensions, settings));
        }

        return builder.build();
    }

    /**
     * Parses a spec value that may be a plain string or an array of strings into a
     * {@code List<String>}. Used for {@code cacheFrom} and {@code dockerComposeFile}.
     */
    private List<String> parseStringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node.isTextual()) {
            result.add(node.asText());
        } else if (node.isArray()) {
            node.forEach(n -> result.add(n.asText()));
        }
        return result;
    }

    private DevcontainerSeed.PortAttributes parsePortAttributes(JsonNode node) {
        String label = node.has("label") ? node.get("label").asText() : null;
        String protocol = node.has("protocol") ? node.get("protocol").asText() : null;
        String onAutoForward = node.has("onAutoForward") ? node.get("onAutoForward").asText() : null;
        Boolean requireLocalPort = node.has("requireLocalPort") ? node.get("requireLocalPort").asBoolean() : null;
        Boolean elevateIfNeeded = node.has("elevateIfNeeded") ? node.get("elevateIfNeeded").asBoolean() : null;
        return new DevcontainerSeed.PortAttributes(label, protocol, onAutoForward, requireLocalPort, elevateIfNeeded);
    }

    private DevcontainerSeed.HostRequirements parseHostRequirements(JsonNode node) {
        Integer cpus = node.has("cpus") ? node.get("cpus").asInt() : null;
        String memory = node.has("memory") ? node.get("memory").asText() : null;
        String storage = node.has("storage") ? node.get("storage").asText() : null;
        Object gpu = null;
        if (node.has("gpu")) {
            JsonNode gpuNode = node.get("gpu");
            if (gpuNode.isBoolean()) {
                gpu = gpuNode.asBoolean();
            } else if (gpuNode.isTextual()) {
                gpu = gpuNode.asText();
            } else if (gpuNode.isObject()) {
                gpu = objectMapper.convertValue(gpuNode, Map.class);
            }
        }
        return new DevcontainerSeed.HostRequirements(cpus, memory, storage, gpu);
    }

    private Map<String, String> parseStringMap(JsonNode node) {
        Map<String, String> map = new HashMap<>();
        node.properties().forEach(entry ->
            map.put(entry.getKey(), entry.getValue().asText()));
        return map;
    }

    private LifecycleCommand parseLifecycleCommand(JsonNode node) {
        if (node.isTextual()) {
            return new LifecycleCommand.Sequential(List.of(node.asText()));
        } else if (node.isArray()) {
            List<String> args = new ArrayList<>();
            node.forEach(n -> args.add(n.asText()));
            return new LifecycleCommand.Sequential(args);
        } else if (node.isObject()) {
            // Spec object form: {"stepName": "cmd" | ["cmd", "arg"], ...} — all steps run in parallel
            Map<String, List<String>> steps = new LinkedHashMap<>();
            node.properties().forEach(e -> {
                JsonNode v = e.getValue();
                List<String> args = new ArrayList<>();
                if (v.isTextual()) {
                    args.add(v.asText());
                } else if (v.isArray()) {
                    v.forEach(n -> args.add(n.asText()));
                }
                steps.put(e.getKey(), args);
            });
            return new LifecycleCommand.Parallel(steps);
        }
        log.warn("Unrecognised lifecycle command node type, ignoring");
        return null;
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
