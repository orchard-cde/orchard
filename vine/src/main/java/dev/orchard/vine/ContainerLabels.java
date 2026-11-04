package dev.orchard.vine;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The label that ties a fruit container to its plot, the compose project label, and lookups by
 * them. Only a fruit's primary container carries the plot label; its compose sidecars, networks and
 * volumes are found through the project label, which Docker Compose and podman-compose both set.
 */
public final class ContainerLabels {

    public static final String PLOT_ID = "orchard.plot.id";
    public static final String COMPOSE_PROJECT = "com.docker.compose.project";

    private ContainerLabels() {}

    /** {@code orchard.plot.id=<plotId>}, for {@code --label} and {@code --id-label}. */
    public static String label(UUID plotId) {
        return PLOT_ID + "=" + plotId;
    }

    /** {@code label=orchard.plot.id=<plotId>}, for {@code ps --filter}. */
    public static String filter(UUID plotId) {
        return "label=" + label(plotId);
    }

    /** {@code label=com.docker.compose.project=<project>}, for {@code ps}, {@code network ls} and {@code volume ls}. */
    public static String composeFilter(String project) {
        return "label=" + COMPOSE_PROJECT + "=" + project;
    }

    /** Ids of the plot's running containers via {@code <cli> ps -q}, or of all of them with {@code includeStopped}. */
    public static List<String> containerIds(CommandRunner runner, String cli, UUID plotId, boolean includeStopped,
                                            long timeoutSeconds)
            throws IOException, InterruptedException {
        return ps(runner, cli, filter(plotId), includeStopped, timeoutSeconds);
    }

    /** Ids of the compose project's running containers, or of all of them with {@code includeStopped}. */
    public static List<String> composeContainerIds(CommandRunner runner, String cli, String project,
                                                   boolean includeStopped, long timeoutSeconds)
            throws IOException, InterruptedException {
        return ps(runner, cli, composeFilter(project), includeStopped, timeoutSeconds);
    }

    /** The compose project the container belongs to, from its labels; works on a stopped container. */
    public static Optional<String> composeProject(CommandRunner runner, String cli, String containerId,
                                                  long timeoutSeconds)
            throws IOException, InterruptedException {
        String format = "{{index .Config.Labels \"" + COMPOSE_PROJECT + "\"}}";
        String project = runner.execute(cli + " inspect --format " + ShellQuote.quote(format) + " "
            + ShellQuote.quote(containerId), timeoutSeconds).trim();
        return project.isEmpty() || project.equals("<no value>") ? Optional.empty() : Optional.of(project);
    }

    /**
     * Removes the compose project's containers, networks and volumes by label, the equivalent of
     * {@code compose down -v} without the compose file podman-compose needs.
     */
    public static void removeComposeProject(CommandRunner runner, String cli, String project, long timeoutSeconds)
            throws IOException, InterruptedException {
        String filter = ShellQuote.quote(composeFilter(project));
        List<String> containers = composeContainerIds(runner, cli, project, true, timeoutSeconds);
        if (!containers.isEmpty()) {
            runner.execute(cli + " rm -f " + ShellQuote.join(containers), timeoutSeconds);
        }
        List<String> networks = parse(runner.execute(cli + " network ls -q --filter " + filter, timeoutSeconds));
        if (!networks.isEmpty()) {
            runner.execute(cli + " network rm " + ShellQuote.join(networks), timeoutSeconds);
        }
        List<String> volumes = parse(runner.execute(cli + " volume ls -q --filter " + filter, timeoutSeconds));
        if (!volumes.isEmpty()) {
            runner.execute(cli + " volume rm -f " + ShellQuote.join(volumes), timeoutSeconds);
        }
    }

    private static List<String> ps(CommandRunner runner, String cli, String filter, boolean includeStopped,
                                   long timeoutSeconds)
            throws IOException, InterruptedException {
        String command = cli + (includeStopped ? " ps -aq" : " ps -q") + " --filter " + ShellQuote.quote(filter);
        return parse(runner.execute(command, timeoutSeconds));
    }

    private static List<String> parse(String out) {
        return out.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
