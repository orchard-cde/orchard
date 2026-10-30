package dev.orchard.gateway.relay;

import dev.orchard.gateway.api.GatewayRoute;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.AbstractCommandSupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Shell/exec channel for a container grove: runs {@code <runtime> exec -i [-t] ... <containerId> /bin/sh}
 * on the gateway host and pumps stdin, stdout and stderr through it, exiting with the process's code.
 * The argv goes straight to the launcher, never through a host shell, so the client's command reaches
 * the container's {@code /bin/sh -c} unaltered. Closing the channel kills the process and its descendants.
 *
 * <p>This is the interim relay: there is no window-change propagation, no sftp subsystem and no port
 * forwarding for container groves.
 */
public class ContainerExecCommand extends AbstractCommandSupport {

    static final String SFTP_REFUSED = "sftp is not available for container groves yet";

    private static final int PUMP_JOIN_MILLIS = 2_000;

    private final GatewayRoute route;
    private final RelayCommand.Mode mode;
    private final String command;
    private final ProcessLauncher launcher;

    private final Object lock = new Object();
    private Process process;
    private boolean destroyed;

    public ContainerExecCommand(GatewayRoute route, RelayCommand.Mode mode, String command, ProcessLauncher launcher) {
        super(command, null);
        this.route = route;
        this.mode = mode;
        this.command = command;
        this.launcher = launcher;
    }

    @Override
    public void run() {
        if (mode == RelayCommand.Mode.SUBSYSTEM) {
            writeError(SFTP_REFUSED);
            onExit(1);
            return;
        }
        Process started;
        try {
            started = launcher.launch(argv(ptyTerm()));
        } catch (IOException | RuntimeException e) {
            log.warn("Container exec failed to start: {}", e.toString());
            writeError("failed to start " + route.runtime().cli() + " exec: " + e.getMessage());
            onExit(255);
            return;
        }
        synchronized (lock) {
            if (destroyed) {
                kill(started);
                return;
            }
            process = started;
        }

        Thread.ofVirtual().name("container-exec-in").start(() -> pump(getInputStream(), started.getOutputStream()));
        Thread outPump = Thread.ofVirtual().name("container-exec-out")
                .start(() -> pump(started.getInputStream(), getOutputStream()));
        Thread errPump = Thread.ofVirtual().name("container-exec-err")
                .start(() -> pump(started.getErrorStream(), getErrorStream()));
        try {
            int exit = started.waitFor();
            outPump.join(PUMP_JOIN_MILLIS);
            errPump.join(PUMP_JOIN_MILLIS);
            onExit(exit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kill(started);
        }
    }

    @Override
    public void destroy(ChannelSession channel) throws Exception {
        Process running;
        synchronized (lock) {
            destroyed = true;
            running = process;
        }
        if (running != null) {
            kill(running);
        }
        super.destroy(channel);
    }

    List<String> argv(String term) {
        List<String> argv = new ArrayList<>(List.of(route.runtime().cli(), "exec", "-i"));
        if (term != null) {
            argv.addAll(List.of("-t", "-e", "TERM=" + term));
        }
        if (route.user() != null) {
            argv.addAll(List.of("-u", route.user()));
        }
        if (route.workspaceFolder() != null) {
            argv.addAll(List.of("-w", route.workspaceFolder()));
        }
        argv.add(route.containerId());
        if (mode == RelayCommand.Mode.EXEC) {
            argv.addAll(List.of("/bin/sh", "-c", command));
        } else {
            argv.addAll(List.of("/bin/sh", "-l"));
        }
        return argv;
    }

    /** MINA records a pty-req as the {@code TERM} environment variable; absent means no PTY was requested. */
    private String ptyTerm() {
        Environment env = getEnvironment();
        return env == null ? null : env.getEnv().get(Environment.ENV_TERM);
    }

    private static void kill(Process process) {
        List<ProcessHandle> descendants;
        try {
            descendants = process.descendants().toList();
        } catch (UnsupportedOperationException e) {
            descendants = List.of();
        }
        process.destroyForcibly();
        descendants.forEach(ProcessHandle::destroyForcibly);
    }

    private void writeError(String message) {
        try {
            OutputStream err = getErrorStream();
            err.write((message + "\n").getBytes(StandardCharsets.UTF_8));
            err.flush();
        } catch (IOException e) {
            log.debug("Could not write to stderr: {}", e.toString());
        }
    }

    private static void pump(InputStream in, OutputStream out) {
        byte[] buffer = new byte[8192];
        try (in; out) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (IOException e) {
            // one side closed; process exit or channel close ends the rest
        }
    }
}
