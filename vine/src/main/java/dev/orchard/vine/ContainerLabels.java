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

    /** Ids of the plot's running containers via {@code <cli> ps -q}, or of all of them with {@code includeStopped}. */
    public static List<String> containerIds(CommandRunner runner, String cli, UUID plotId, boolean includeStopped,
                                            long timeoutSeconds)
            throws IOException, InterruptedException {
        String command = cli + (includeStopped ? " ps -aq" : " ps -q") + " --filter " + ShellQuote.quote(filter(plotId));
        return parse(runner.execute(command, timeoutSeconds));
    }

    private static List<String> parse(String out) {
        return out.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
