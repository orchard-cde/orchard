package dev.orchard.vine;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** The label that ties a fruit container to its plot, and the lookup of a plot's containers by it. */
public final class ContainerLabels {

    public static final String PLOT_ID = "orchard.plot.id";

    private ContainerLabels() {}

    /** {@code orchard.plot.id=<plotId>}, for {@code --label} and {@code --id-label}. */
    public static String label(UUID plotId) {
        return PLOT_ID + "=" + plotId;
    }

    /** {@code label=orchard.plot.id=<plotId>}, for {@code ps --filter}. */
    public static String filter(UUID plotId) {
        return "label=" + label(plotId);
    }

    /** Ids of the plot's containers, via {@code <cli> ps -q} or, with {@code includeStopped}, {@code ps -aq}. */
    public static List<String> containerIds(CommandRunner runner, String cli, UUID plotId, boolean includeStopped)
            throws IOException, InterruptedException {
        return parse(runner.execute(psCommand(cli, plotId, includeStopped, null)));
    }

    /**
     * Like {@link #containerIds(CommandRunner, String, UUID, boolean)} with a timeout, and, when
     * {@code status} is non-null, an extra {@code --filter status=<status>}.
     */
    public static List<String> containerIds(CommandRunner runner, String cli, UUID plotId, boolean includeStopped,
                                            String status, long timeoutSeconds)
            throws IOException, InterruptedException {
        return parse(runner.execute(psCommand(cli, plotId, includeStopped, status), timeoutSeconds));
    }

    private static String psCommand(String cli, UUID plotId, boolean includeStopped, String status) {
        String command = cli + (includeStopped ? " ps -aq" : " ps -q") + " --filter " + ShellQuote.quote(filter(plotId));
        return status == null ? command : command + " --filter " + ShellQuote.quote("status=" + status);
    }

    private static List<String> parse(String out) {
        return out.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
