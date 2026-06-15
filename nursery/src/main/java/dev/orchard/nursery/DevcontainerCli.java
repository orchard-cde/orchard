package dev.orchard.nursery;

import dev.orchard.core.model.Seedling;
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
import java.util.function.Function;

/**
 * Drives {@code @devcontainers/cli} over SSH on a {@link Seedling}.
 * Spec: Locked decisions #1-19 of {@code docs/superpowers/specs/2026-06-15-issue-74-features-runtime-design.md}.
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
    private final Function<Seedling, CommandRunner> runnerFactory;

    public DevcontainerCli(DevcontainerCliConfig config) {
        this(config, SshExecutor::new);
    }

    /** Test seam. Production callers use the single-arg constructor. */
    DevcontainerCli(DevcontainerCliConfig config, Function<Seedling, CommandRunner> runnerFactory) {
        this.config = config;
        this.runnerFactory = runnerFactory;
    }

    /**
     * Runs {@code devcontainer up} against /workspace on the seedling. Each CLI JSON log
     * line is fed to {@code rawLineConsumer} as it arrives; phase-transition events
     * are derived by the caller (FruitGrower).
     *
     * @return the parsed {@link DevcontainerCliResult} (outcome=success line).
     * @throws DevcontainerCliException on outcome=error or a missing outcome line.
     */
    public DevcontainerCliResult up(Seedling seedling, UUID fruitId, String containerName,
                                    Consumer<String> rawLineConsumer) throws IOException, InterruptedException {
        String cmd = "devcontainer up"
            + " --workspace-folder /workspace"
            + " --log-format json"
            + " --skip-post-attach"
            + " --remove-existing-container"
            + " --id-label orchard.fruit.id=" + fruitId
            + " --id-label orchard.fruit.name=" + shellEscape(containerName);

        AtomicReference<String> outcomeLine = new AtomicReference<>();
        runnerFactory.apply(seedling).executeStreaming(cmd, line -> {
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

    /** Run a command inside the fruit's container. Used by FruitGrower.attach() for postAttachCommand. */
    public void exec(Seedling seedling, String command) throws IOException, InterruptedException {
        String cmd = "devcontainer exec --workspace-folder /workspace -- " + command;
        runnerFactory.apply(seedling).execute(cmd, config.execTimeoutSeconds());
    }

    /** Fetch the actual container name post-up so {@code Fruit.containerName()} reflects reality. */
    public String inspectContainerName(Seedling seedling, String containerId) throws IOException, InterruptedException {
        String name = runnerFactory.apply(seedling)
            .execute("docker inspect " + containerId + " --format '{{.Name}}'")
            .trim();
        // docker inspect returns "/name" with a leading slash; strip it.
        return name.startsWith("/") ? name.substring(1) : name;
    }

    private static String shellEscape(String s) {
        if (s == null) return "''";
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
