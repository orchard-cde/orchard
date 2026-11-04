package dev.orchard.vine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * Immutable builder for the argv of {@code <cli> exec} into a container. Flags come out in a fixed
 * order: {@code -i}, {@code -t}, {@code -e}, {@code -u}, {@code -w}, then the container id and the shell.
 */
public final class ContainerExec {

    private final String cli;
    private final String containerId;
    private final boolean stdin;
    private final boolean tty;
    private final SequencedMap<String, String> env;
    private final String user;
    private final String workdir;

    private ContainerExec(String cli, String containerId, boolean stdin, boolean tty,
                          SequencedMap<String, String> env, String user, String workdir) {
        this.cli = cli;
        this.containerId = containerId;
        this.stdin = stdin;
        this.tty = tty;
        this.env = env;
        this.user = user;
        this.workdir = workdir;
    }

    public static ContainerExec of(String cli, String containerId) {
        return new ContainerExec(cli, containerId, false, false, new LinkedHashMap<>(), null, null);
    }

    /** Runs as {@code user}; {@code null} means no {@code -u}. */
    public ContainerExec user(String user) {
        return new ContainerExec(cli, containerId, stdin, tty, env, user, workdir);
    }

    /** Starts in {@code workdir}; {@code null} means no {@code -w}. */
    public ContainerExec workdir(String workdir) {
        return new ContainerExec(cli, containerId, stdin, tty, env, user, workdir);
    }

    public ContainerExec env(String key, String value) {
        SequencedMap<String, String> next = new LinkedHashMap<>(env);
        next.put(key, value);
        return new ContainerExec(cli, containerId, stdin, tty, next, user, workdir);
    }

    /** {@code -i} when {@code stdin}, {@code -t} when {@code tty}. */
    public ContainerExec interactive(boolean stdin, boolean tty) {
        return new ContainerExec(cli, containerId, stdin, tty, env, user, workdir);
    }

    /** Runs {@code command} with {@code /bin/sh -c}. */
    public Terminal shell(String command) {
        return new Terminal(List.of("/bin/sh", "-c", command));
    }

    /** Starts a login shell: {@code /bin/sh -il} when {@code interactiveFlag}, else {@code /bin/sh -l}. */
    public Terminal loginShell(boolean interactiveFlag) {
        return new Terminal(List.of("/bin/sh", interactiveFlag ? "-il" : "-l"));
    }

    /** A finished exec whose argv is fixed. */
    public final class Terminal {

        private final List<String> shell;

        private Terminal(List<String> shell) {
            this.shell = shell;
        }

        public List<String> argv() {
            List<String> argv = new ArrayList<>(List.of(cli, "exec"));
            if (stdin) {
                argv.add("-i");
            }
            if (tty) {
                argv.add("-t");
            }
            for (Map.Entry<String, String> e : env.entrySet()) {
                argv.addAll(List.of("-e", e.getKey() + "=" + e.getValue()));
            }
            if (user != null) {
                argv.addAll(List.of("-u", user));
            }
            if (workdir != null) {
                argv.addAll(List.of("-w", workdir));
            }
            argv.add(containerId);
            argv.addAll(shell);
            return List.copyOf(argv);
        }
    }
}
