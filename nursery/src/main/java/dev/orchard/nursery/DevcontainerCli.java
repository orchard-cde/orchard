package dev.orchard.nursery;

import dev.orchard.vine.CommandRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import tools.jackson.databind.DeserializationFeature;
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
@RegisterReflectionForBinding({DevcontainerCliResult.class, CliError.class, DevcontainerCli.Outcome.class})
public class DevcontainerCli {

    private static final Logger log = LoggerFactory.getLogger(DevcontainerCli.class);
    private static final ObjectMapper mapper = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    record Outcome(String outcome) {
        boolean isSuccess() {
            return "success".equals(outcome);
        }
    }

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
            if (isOutcomeLine(line)) {
                outcomeLine.set(line);
            }
        }, config.upTimeoutSeconds());

        if (outcomeLine.get() == null) {
            throw new DevcontainerCliException(new CliError(
                "no outcome line", "devcontainer up emitted no result JSON — possible CLI crash",
                null, null, null, null));
        }

        if (!mapper.readValue(outcomeLine.get(), Outcome.class).isSuccess()) {
            throw new DevcontainerCliException(mapper.readValue(outcomeLine.get(), CliError.class));
        }
        return mapper.readValue(outcomeLine.get(), DevcontainerCliResult.class);
    }

    /**
     * Runs {@code devcontainer run-user-commands} against an existing container, as the remote user
     * in the workspace folder with variable substitution, exactly as {@code up} ran them. The CLI's
     * markers skip create-time hooks already run in this container and rerun {@code postStartCommand}
     * once per container start. {@code postAttachCommand} is skipped, as it is for {@code up}.
     *
     * @throws DevcontainerCliException on outcome=error or a missing outcome line
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
            if (isOutcomeLine(line)) {
                outcomeLine = line;
            }
        }
        if (outcomeLine == null) {
            throw new DevcontainerCliException(new CliError(
                "no outcome line", "devcontainer run-user-commands emitted no result JSON — possible CLI crash",
                null, containerId, null, null));
        }
        if (!mapper.readValue(outcomeLine, Outcome.class).isSuccess()) {
            CliError error = mapper.readValue(outcomeLine, CliError.class);
            throw new DevcontainerCliException(error.containerId() != null ? error : new CliError(
                error.message(), error.description(), error.disallowedFeatureId(),
                containerId, error.didStopContainer(), error.learnMoreUrl()));
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

    /**
     * The outcome line is the only one with an "outcome" key (matched as a JSON key prefix to avoid
     * false positives on log messages that mention "outcome" in a value).
     */
    private static boolean isOutcomeLine(String line) {
        return line.contains("\"outcome\":\"");
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
