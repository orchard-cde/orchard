package dev.orchard.harvest;

import dev.orchard.core.model.DevfileSeed;
import dev.orchard.core.model.LifecycleCommand;
import dev.orchard.core.model.Seed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * Parses {@code devfile.yaml} files into {@link DevfileSeed} objects.
 *
 * <p>Supports devfile schema versions 2.x. Only {@code container} components are mapped;
 * {@code kubernetes} / {@code openshift} components are tracked separately (#86).
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

    /** Only devfile 2.x is supported. */
    private static final String SUPPORTED_SCHEMA_PREFIX = "2.";

    private static final ObjectMapper yamlMapper = LenientBinding.configure(YAMLMapper.builder(), LenientBinding.Mode.LENIENT)
        .build();

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
        DevfileYaml devfile = yamlMapper.readValue(yamlContent, DevfileYaml.class);

        // schemaVersion is required; only 2.x is supported
        String schemaVersion = devfile != null ? devfile.schemaVersion() : null;
        if (schemaVersion == null || schemaVersion.isBlank()) {
            throw new DevfileParseException("Missing schemaVersion — not a valid devfile");
        }
        if (!schemaVersion.startsWith(SUPPORTED_SCHEMA_PREFIX)) {
            throw new DevfileParseException(
                "Unsupported devfile schemaVersion '%s'; only 2.x is supported".formatted(schemaVersion));
        }

        String workspaceName = devfile.metadata() != null ? devfile.metadata().name() : null;

        // Top-level commands/events → preStart/postStart LifecycleCommands (shared across
        // whichever container component is picked below; devfile events are workspace-wide,
        // not per-component).
        Map<String, String> commandLineById = parseExecCommands(devfile.commands());
        DevfileYaml.Events events = devfile.events();
        LifecycleCommand preStartCommand =
            resolveEventCommand("preStart", events != null ? events.preStart() : null, commandLineById);
        LifecycleCommand postStartCommand =
            resolveEventCommand("postStart", events != null ? events.postStart() : null, commandLineById);

        if (devfile.components() == null || devfile.components().isEmpty()) {
            log.debug("Devfile has no components array");
            return Optional.empty();
        }

        for (DevfileYaml.Component component : devfile.components()) {
            if (component == null || component.container() == null) {
                continue; // skip kubernetes/openshift/image/volume components
            }
            return Optional.of(mapContainerComponent(
                workspaceName, component.name(), component.container(), preStartCommand, postStartCommand));
        }

        log.debug("Devfile has no container components");
        return Optional.empty();
    }

    /**
     * Maps the top-level {@code commands} array to a {@code commandId -> commandLine} lookup.
     * Only {@code exec} commands are resolved; {@code apply}/{@code composite} commands are
     * skipped (unsupported — see {@link DevfileSeed} class Javadoc).
     */
    private Map<String, String> parseExecCommands(List<DevfileYaml.Command> commands) {
        Map<String, String> commandLineById = new LinkedHashMap<>();
        if (commands == null) {
            return commandLineById;
        }
        for (DevfileYaml.Command command : commands) {
            if (command == null || isBlank(command.id()) || command.exec() == null) {
                continue;
            }
            String commandLine = command.exec().commandLine();
            if (!isBlank(commandLine)) {
                commandLineById.put(command.id(), commandLine);
            }
        }
        return commandLineById;
    }

    /**
     * Resolves an {@code events.<eventName>} command-id list against {@code commandLineById}
     * into a single {@link LifecycleCommand.Sequential}. Multiple command IDs are composed with
     * {@code &&} into one shell line — matching {@link LifecycleCommand.Sequential}'s existing
     * single-shell-line convention (see {@code DevcontainerParser}'s textual-form handling).
     * IDs with no matching {@code exec} command are skipped and logged.
     * Returns {@code null} if the event is absent or resolves to no commands.
     */
    private LifecycleCommand resolveEventCommand(
            String eventName, List<String> eventCommandIds, Map<String, String> commandLineById) {
        if (eventCommandIds == null || eventCommandIds.isEmpty()) {
            return null;
        }
        List<String> commandLines = new ArrayList<>();
        for (String id : eventCommandIds) {
            String commandLine = id != null ? commandLineById.get(id) : null;
            if (commandLine != null) {
                commandLines.add(commandLine);
            } else {
                log.warn("devfile events.{} references unknown or non-exec command id '{}', skipping",
                    eventName, id);
            }
        }
        if (commandLines.isEmpty()) {
            return null;
        }
        return new LifecycleCommand.Sequential(List.of(String.join(" && ", commandLines)));
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
     * Resolved {@code preStart}/{@code postStart} lifecycle commands are set from
     * top-level {@code events} resolved against {@code commands}.
     */
    private DevfileSeed mapContainerComponent(
            String workspaceName, String componentName, DevfileYaml.Container container,
            LifecycleCommand preStartCommand, LifecycleCommand postStartCommand) {
        DevfileSeed.Builder builder = Seed.devfile();

        ifPresent(workspaceName, builder::name);
        ifPresent(componentName, builder::componentName);
        ifPresent(container.image(), builder::image);

        if (container.env() != null) {
            Map<String, String> env = new LinkedHashMap<>();
            for (DevfileYaml.Env entry : container.env()) {
                if (entry != null && !isBlank(entry.name())) {
                    env.put(entry.name(), entry.value() != null ? entry.value() : "");
                }
            }
            if (!env.isEmpty()) {
                builder.containerEnv(env);
            }
        }

        if (container.endpoints() != null) {
            List<String> ports = new ArrayList<>();
            for (DevfileYaml.Endpoint endpoint : container.endpoints()) {
                if (endpoint != null && endpoint.targetPort() != null && endpoint.targetPort() > 0) {
                    ports.add(String.valueOf(endpoint.targetPort()));
                }
            }
            if (!ports.isEmpty()) {
                builder.forwardPorts(ports);
            }
        }

        ifPresent(container.memoryLimit(), builder::memoryLimit);
        ifPresent(container.memoryRequest(), builder::memoryRequest);
        ifPresent(container.cpuLimit(), builder::cpuLimit);
        ifPresent(container.cpuRequest(), builder::cpuRequest);

        builder.preStartCommand(preStartCommand);
        builder.postStartCommand(postStartCommand);

        return builder.build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void ifPresent(String value, Consumer<String> setter) {
        if (!isBlank(value)) {
            setter.accept(value);
        }
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
