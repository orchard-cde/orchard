package dev.orchard.nursery;

import dev.orchard.vine.CommandRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Drives {@code @devcontainers/cli} over SSH via an injected {@link CommandRunner}.
 *
 * <p>The CLI's stdout (with {@code --log-format json}) is a stream of JSON objects, one per
 * line. Each log line has a {@code type} field; the terminal line has an {@code outcome}
 * field. We parse the outcome line for the result and pass every line through to a
 * Consumer for callers who want to derive progress events.
 */
@RegisterReflectionForBinding({DevcontainerCliResult.class, CliError.class})
public class DevcontainerCli {

    private static final Logger log = LoggerFactory.getLogger(DevcontainerCli.class);
    private static final ObjectMapper mapper = JsonMapper.builder().build();

    private final DevcontainerCliConfig config;

    public DevcontainerCli(DevcontainerCliConfig config) {
        this.config = config;
    }

    /**
     * Runs {@code devcontainer up} against {@code workspacePath} via the given runner. Each CLI
     * JSON log line is fed to {@code rawLineConsumer} as it arrives; phase-transition events
     * are derived by the caller (FruitGrower).
     *
     * @return the parsed {@link DevcontainerCliResult} (outcome=success line).
     * @throws DevcontainerCliException on outcome=error or a missing outcome line.
     */
    public DevcontainerCliResult up(CommandRunner runner, String workspacePath, UUID fruitId,
                                    String containerName, Consumer<String> rawLineConsumer)
            throws IOException, InterruptedException {
        String cmd = "devcontainer up"
            + " --workspace-folder " + workspacePath
            + " --log-format json"
            + " --skip-post-attach"
            + " --remove-existing-container"
            + " --id-label orchard.fruit.id=" + fruitId
            + " --id-label orchard.fruit.name=" + shellEscape(containerName);

        AtomicReference<String> outcomeLine = new AtomicReference<>();
        runner.executeStreaming(cmd, line -> {
            rawLineConsumer.accept(line);
            // The outcome line is the only one with an "outcome" key (matched as a JSON key prefix
            // to avoid false positives on log messages that mention "outcome" in a value).
            if (line.contains("\"outcome\":\"")) {
                outcomeLine.set(line);
            }
        }, config.upTimeoutSeconds());

        if (outcomeLine.get() == null) {
            throw new DevcontainerCliException(new CliError(
                "no outcome line", "devcontainer up emitted no result JSON — possible CLI crash",
                null, null, null, null));
        }

        JsonNode node = mapper.readTree(outcomeLine.get());
        String outcome = node.has("outcome") ? node.get("outcome").asText() : null;
        if (!"success".equals(outcome)) {
            throw new DevcontainerCliException(new CliError(
                node.path("message").asText(null),
                node.path("description").asText(null),
                node.path("disallowedFeatureId").asText(null),
                node.path("containerId").asText(null),
                node.has("didStopContainer") ? node.get("didStopContainer").asBoolean() : null,
                node.path("learnMoreUrl").asText(null)));
        }

        return new DevcontainerCliResult(
            node.path("containerId").asText(null),
            node.path("composeProjectName").asText(null),
            node.path("remoteUser").asText(null),
            node.path("remoteWorkspaceFolder").asText(null));
    }

    /**
     * Runs {@code devcontainer run-user-commands} against an existing container, as the remote user
     * in the workspace folder with variable substitution, exactly as {@code up} ran them. The CLI's
     * markers skip create-time hooks already run in this container and rerun {@code postStartCommand}
     * once per container start. {@code postAttachCommand} is skipped, as it is for {@code up}.
     *
     * @throws DevcontainerCliException on outcome=error
     * @throws IOException if the CLI exits non-zero
     */
    public void runUserCommands(CommandRunner runner, String workspacePath, String containerId)
            throws IOException, InterruptedException {
        String cmd = "devcontainer run-user-commands"
            + " --workspace-folder " + workspacePath
            + " --container-id " + containerId
            + " --log-format json"
            + " --skip-post-attach";
        String output = runner.execute(cmd, config.upTimeoutSeconds());
        String outcomeLine = null;
        for (String line : output.split("\n")) {
            if (line.contains("\"outcome\":\"")) {
                outcomeLine = line;
            }
        }
        if (outcomeLine == null) {
            return;
        }
        JsonNode node = mapper.readTree(outcomeLine);
        if (!"success".equals(node.path("outcome").asText(null))) {
            throw new DevcontainerCliException(new CliError(
                node.path("message").asText(null),
                node.path("description").asText(null),
                null, containerId, null, null));
        }
    }

    /** Run a command inside the fruit's container. Used by FruitGrower.attach() for postAttachCommand. */
    public void exec(CommandRunner runner, String workspacePath, String command) throws IOException, InterruptedException {
        String cmd = "devcontainer exec --workspace-folder " + workspacePath + " -- " + command;
        runner.execute(cmd, config.execTimeoutSeconds());
    }

    /** Fetch the actual container name post-up so {@code Fruit.containerName()} reflects reality. */
    public String inspectContainerName(CommandRunner runner, String containerId) throws IOException, InterruptedException {
        String name = runner
            .execute("docker inspect " + containerId + " --format '{{.Name}}'")
            .trim();
        // docker inspect returns "/name" with a leading slash; strip it.
        return name.startsWith("/") ? name.substring(1) : name;
    }

    private static String shellEscape(String s) {
        if (s == null) {
            return "''";
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }

    public static class DevcontainerCliException extends RuntimeException {
        private final CliError error;
        public DevcontainerCliException(CliError error) {
            super(error.description() != null ? error.description() : error.message());
            this.error = error;
        }
        public CliError error() { return error; }
    }
}
