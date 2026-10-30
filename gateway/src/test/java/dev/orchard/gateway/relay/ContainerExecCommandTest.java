package dev.orchard.gateway.relay;

import dev.orchard.core.model.ContainerRuntime;
import dev.orchard.gateway.api.GatewayRoute;
import dev.orchard.gateway.api.RouteKind;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.channel.ChannelSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerExecCommandTest {

    private static GatewayRoute podmanRoute(String user, String workspaceFolder) {
        return new GatewayRoute(UUID.randomUUID(), UUID.randomUUID(), "FLOURISHING", RouteKind.CONTAINER,
                null, null, ContainerRuntime.PODMAN, "c0ffee", user, workspaceFolder);
    }

    @Test
    @Timeout(10)
    void argv_shellWithPty_userAndWorkdir() throws Exception {
        ScriptedLauncher launcher = new ScriptedLauncher(ScriptedProcess.exiting(0, "", ""));
        Run run = Run.start(new ContainerExecCommand(podmanRoute("vscode", "/workspaces/app"),
                RelayCommand.Mode.SHELL, null, launcher), "", "xterm-256color");

        run.awaitExit();

        assertThat(launcher.argvs).containsExactly(List.of(
                "podman", "exec", "-i", "-t", "-e", "TERM=xterm-256color", "-u", "vscode",
                "-w", "/workspaces/app", "c0ffee", "/bin/sh", "-l"));
    }

    @Test
    @Timeout(10)
    void argv_podmanShellWithPty_hasTtyFlag() throws Exception {
        ScriptedLauncher launcher = new ScriptedLauncher(ScriptedProcess.exiting(0, "", ""));
        Run run = Run.start(new ContainerExecCommand(podmanRoute(null, null),
                RelayCommand.Mode.SHELL, null, launcher), "", "xterm");

        run.awaitExit();

        assertThat(launcher.argvs).containsExactly(List.of(
                "podman", "exec", "-i", "-t", "-e", "TERM=xterm", "c0ffee", "/bin/sh", "-l"));
    }

    @Test
    @Timeout(10)
    void argv_dockerShellWithPty_noTtyFlag_interactiveShell() throws Exception {
        ScriptedLauncher launcher = new ScriptedLauncher(ScriptedProcess.exiting(0, "", ""));
        Run run = Run.start(new ContainerExecCommand(dockerRoute(),
                RelayCommand.Mode.SHELL, null, launcher), "", "xterm-256color");

        run.awaitExit();

        assertThat(launcher.argvs).containsExactly(List.of(
                "docker", "exec", "-i", "-e", "TERM=xterm-256color", "-u", "vscode",
                "-w", "/workspaces/app", "abc123", "/bin/sh", "-il"));
    }

    @Test
    @Timeout(10)
    void argv_dockerShellWithoutPty_interactiveShell() throws Exception {
        ScriptedLauncher launcher = new ScriptedLauncher(ScriptedProcess.exiting(0, "", ""));
        Run run = Run.start(new ContainerExecCommand(dockerRoute(),
                RelayCommand.Mode.SHELL, null, launcher), "", null);

        run.awaitExit();

        assertThat(launcher.argvs).containsExactly(List.of(
                "docker", "exec", "-i", "-u", "vscode", "-w", "/workspaces/app", "abc123", "/bin/sh", "-il"));
    }

    @Test
    @Timeout(10)
    void nullRuntime_exits255WithMessage() throws Exception {
        UUID groveId = UUID.randomUUID();
        GatewayRoute route = new GatewayRoute(groveId, UUID.randomUUID(), "FLOURISHING", RouteKind.CONTAINER,
                null, null, null, "c0ffee", null, null);
        ScriptedLauncher launcher = new ScriptedLauncher(ScriptedProcess.exiting(0, "", ""));
        Run run = Run.start(new ContainerExecCommand(route, RelayCommand.Mode.EXEC, "true", launcher), "", null);

        assertThat(run.awaitExit()).isEqualTo(255);
        assertThat(run.err()).isEqualTo("container route for grove " + groveId + " has no runtime\n");
        assertThat(launcher.argvs).isEmpty();
    }

    @Test
    @Timeout(30)
    void exitStatus_sentOnlyAfterAllOutputDelivered() throws Exception {
        String big = "x".repeat(80 * 1024);
        ScriptedLauncher launcher = new ScriptedLauncher(ScriptedProcess.exiting(0, big, ""));
        ContainerExecCommand command = new ContainerExecCommand(podmanRoute(null, null),
                RelayCommand.Mode.EXEC, "cat big", launcher);
        SlowStream slowClient = new SlowStream(300);
        CompletableFuture<Integer> deliveredAtExit = new CompletableFuture<>();
        command.setInputStream(new ByteArrayInputStream(new byte[0]));
        command.setOutputStream(slowClient);
        command.setErrorStream(new ByteArrayOutputStream());
        command.setExitCallback((code, message, closeImmediately) -> deliveredAtExit.complete(slowClient.size()));
        Environment env = mock(Environment.class);
        when(env.getEnv()).thenReturn(Map.of());
        command.start(mock(ChannelSession.class), env);

        assertThat(deliveredAtExit.get(25, TimeUnit.SECONDS)).isEqualTo(big.length());
    }

    @Test
    @Timeout(10)
    void argv_execWithoutPty_noUser() throws Exception {
        ScriptedLauncher launcher = new ScriptedLauncher(ScriptedProcess.exiting(0, "", ""));
        String command = "echo 'a b' && ls \"$HOME\"; rm -rf /tmp/x";
        Run run = Run.start(new ContainerExecCommand(podmanRoute(null, "/workspaces/app"),
                RelayCommand.Mode.EXEC, command, launcher), "", null);

        run.awaitExit();

        assertThat(launcher.argvs).containsExactly(List.of(
                "podman", "exec", "-i", "-w", "/workspaces/app", "c0ffee", "/bin/sh", "-c", command));
    }

    @Test
    @Timeout(10)
    void argv_noUserNoWorkdir_dockerRuntime() throws Exception {
        ScriptedLauncher launcher = new ScriptedLauncher(ScriptedProcess.exiting(0, "", ""));
        GatewayRoute route = new GatewayRoute(UUID.randomUUID(), UUID.randomUUID(), "FLOURISHING",
                RouteKind.CONTAINER, null, null, ContainerRuntime.DOCKER, "abc123", null, null);
        Run run = Run.start(new ContainerExecCommand(route, RelayCommand.Mode.EXEC, "true", launcher), "", null);

        run.awaitExit();

        assertThat(launcher.argvs).containsExactly(List.of(
                "docker", "exec", "-i", "abc123", "/bin/sh", "-c", "true"));
    }

    @Test
    @Timeout(10)
    void exitCode_propagates() throws Exception {
        ScriptedLauncher launcher = new ScriptedLauncher(ScriptedProcess.exiting(7, "hello\n", ""));
        Run run = Run.start(new ContainerExecCommand(podmanRoute(null, null),
                RelayCommand.Mode.EXEC, "exit 7", launcher), "", null);

        assertThat(run.awaitExit()).isEqualTo(7);
        assertThat(run.out()).isEqualTo("hello\n");
    }

    @Test
    @Timeout(10)
    void stderr_isRelayed() throws Exception {
        ScriptedLauncher launcher = new ScriptedLauncher(ScriptedProcess.exiting(1, "", "boom\n"));
        Run run = Run.start(new ContainerExecCommand(podmanRoute(null, null),
                RelayCommand.Mode.EXEC, "false", launcher), "", null);

        assertThat(run.awaitExit()).isEqualTo(1);
        assertThat(run.err()).isEqualTo("boom\n");
        assertThat(run.out()).isEmpty();
    }

    @Test
    @Timeout(10)
    void stdin_isRelayed() throws Exception {
        ScriptedProcess process = ScriptedProcess.exitingAfterStdinCloses(0);
        Run run = Run.start(new ContainerExecCommand(podmanRoute(null, null),
                RelayCommand.Mode.EXEC, "cat", new ScriptedLauncher(process)), "line one\n", null);

        assertThat(run.awaitExit()).isEqualTo(0);
        assertThat(process.stdin.flushed()).isEqualTo("line one\n");
    }

    @Test
    @Timeout(10)
    void interactiveBytes_areFlushedWhileTheShellRuns() throws Exception {
        ScriptedProcess process = ScriptedProcess.printingThenRunning("$ ");
        ContainerExecCommand command = new ContainerExecCommand(podmanRoute(null, null),
                RelayCommand.Mode.SHELL, null, new ScriptedLauncher(process));
        Run run = Run.startWithInput(command, new ThenBlockingInputStream("ls\n"), "xterm");

        long deadline = System.currentTimeMillis() + 5_000;
        while ((!run.out().equals("$ ") || !process.stdin.flushed().equals("ls\n"))
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertThat(run.out()).isEqualTo("$ ");
        assertThat(process.stdin.flushed()).isEqualTo("ls\n");
        assertThat(process.isAlive()).isTrue();
        command.destroy(run.channel);
    }

    @Test
    @Timeout(10)
    void launchFailure_exits255WithMessage() throws Exception {
        ProcessLauncher failing = argv -> {
            throw new IOException("Cannot run program \"podman\": error=2, No such file or directory");
        };
        Run run = Run.start(new ContainerExecCommand(podmanRoute(null, null),
                RelayCommand.Mode.EXEC, "true", failing), "", null);

        assertThat(run.awaitExit()).isEqualTo(255);
        assertThat(run.err()).contains("No such file or directory");
    }

    @Test
    @Timeout(10)
    void subsystem_refusedWithoutLaunching() throws Exception {
        ScriptedLauncher launcher = new ScriptedLauncher(ScriptedProcess.exiting(0, "", ""));
        Run run = Run.start(new ContainerExecCommand(podmanRoute(null, null),
                RelayCommand.Mode.SUBSYSTEM, "sftp", launcher), "", null);

        assertThat(run.awaitExit()).isEqualTo(1);
        assertThat(run.err()).isEqualTo("sftp is not available for container groves yet\n");
        assertThat(launcher.argvs).isEmpty();
    }

    @Test
    @Timeout(10)
    void close_killsTheProcessAndItsDescendants() throws Exception {
        ProcessHandle child = mock(ProcessHandle.class);
        ScriptedProcess process = ScriptedProcess.runningUntilKilled(child);
        ScriptedLauncher launcher = new ScriptedLauncher(process);
        ContainerExecCommand command = new ContainerExecCommand(podmanRoute(null, null),
                RelayCommand.Mode.SHELL, null, launcher);
        Run run = Run.start(command, null, "xterm");
        assertThat(launcher.launched.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(process.isAlive()).isTrue();

        command.destroy(run.channel);

        assertThat(process.forciblyDestroyed).isTrue();
        verify(child, atLeastOnce()).destroyForcibly();
    }

    @Test
    @Timeout(10)
    void close_beforeLaunchReturns_stillKillsTheProcess() throws Exception {
        ScriptedProcess process = ScriptedProcess.runningUntilKilled();
        CountDownLatch launching = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ProcessLauncher slow = argv -> {
            launching.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return process;
        };
        ContainerExecCommand command = new ContainerExecCommand(podmanRoute(null, null),
                RelayCommand.Mode.EXEC, "sleep 1000", slow);
        Run run = Run.start(command, null, null);
        assertThat(launching.await(5, TimeUnit.SECONDS)).isTrue();

        command.destroy(run.channel);
        release.countDown();

        long deadline = System.currentTimeMillis() + 5_000;
        while (!process.forciblyDestroyed && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(process.forciblyDestroyed).isTrue();
    }

    private static GatewayRoute dockerRoute() {
        return new GatewayRoute(UUID.randomUUID(), UUID.randomUUID(), "FLOURISHING", RouteKind.CONTAINER,
                null, null, ContainerRuntime.DOCKER, "abc123", "vscode", "/workspaces/app");
    }

    /** A client that drains slowly: each write takes {@code delayMillis}. */
    private static final class SlowStream extends OutputStream {
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();
        private final long delayMillis;

        SlowStream(long delayMillis) {
            this.delayMillis = delayMillis;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            synchronized (received) {
                received.write(b, off, len);
            }
        }

        int size() {
            synchronized (received) {
                return received.size();
            }
        }
    }

    private record Run(ChannelSession channel, FlushRecordingStream stdout, FlushRecordingStream stderr,
                       CompletableFuture<Integer> exit) {

        static Run start(ContainerExecCommand command, String stdin, String term) throws IOException {
            return startWithInput(command, stdin == null ? new BlockingInputStream()
                    : new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)), term);
        }

        static Run startWithInput(ContainerExecCommand command, InputStream stdin, String term) throws IOException {
            FlushRecordingStream out = new FlushRecordingStream();
            FlushRecordingStream err = new FlushRecordingStream();
            CompletableFuture<Integer> exit = new CompletableFuture<>();
            command.setInputStream(stdin);
            command.setOutputStream(out);
            command.setErrorStream(err);
            command.setExitCallback((code, message, closeImmediately) -> exit.complete(code));
            Environment env = mock(Environment.class);
            when(env.getEnv()).thenReturn(term == null ? Map.of() : Map.of(Environment.ENV_TERM, term));
            ChannelSession channel = mock(ChannelSession.class);
            command.start(channel, env);
            return new Run(channel, out, err, exit);
        }

        int awaitExit() throws InterruptedException, ExecutionException, java.util.concurrent.TimeoutException {
            return exit.get(5, TimeUnit.SECONDS);
        }

        String out() {
            return stdout.flushed();
        }

        String err() {
            return stderr.flushed();
        }
    }

    /** Exposes only what was flushed, the way MINA's channel stream and a process's stdin pipe deliver bytes. */
    private static final class FlushRecordingStream extends OutputStream {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private final ByteArrayOutputStream delivered = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) {
            pending.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            pending.write(b, off, len);
        }

        @Override
        public synchronized void flush() {
            delivered.writeBytes(pending.toByteArray());
            pending.reset();
        }

        @Override
        public void close() {
            flush();
        }

        synchronized String flushed() {
            return delivered.toString(StandardCharsets.UTF_8);
        }
    }

    /** Yields {@code first}, then blocks like an idle interactive client. */
    private static final class ThenBlockingInputStream extends InputStream {
        private final ByteArrayInputStream first;
        private final BlockingInputStream rest = new BlockingInputStream();

        ThenBlockingInputStream(String first) {
            this.first = new ByteArrayInputStream(first.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public int read() throws IOException {
            int b = first.read();
            return b >= 0 ? b : rest.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = first.read(b, off, len);
            return n > 0 ? n : rest.read(b, off, len);
        }

        @Override
        public void close() {
            rest.close();
        }
    }

    private static final class ScriptedLauncher implements ProcessLauncher {
        final List<List<String>> argvs = new CopyOnWriteArrayList<>();
        final CountDownLatch launched = new CountDownLatch(1);
        private final ScriptedProcess process;

        ScriptedLauncher(ScriptedProcess process) {
            this.process = process;
        }

        @Override
        public Process launch(List<String> argv) {
            argvs.add(List.copyOf(argv));
            launched.countDown();
            return process;
        }
    }

    /** A client's stdin that stays open until the channel is torn down. */
    private static final class BlockingInputStream extends InputStream {
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return read();
        }

        @Override
        public int read() throws IOException {
            try {
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            return -1;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class ScriptedProcess extends Process {
        final FlushRecordingStream stdin = new FlushRecordingStream();
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final byte[] stdout;
        private final byte[] stderr;
        private final boolean exitOnStdinClose;
        private final List<ProcessHandle> descendants;
        private int exitCode;
        volatile boolean forciblyDestroyed;

        private ScriptedProcess(byte[] stdout, byte[] stderr, boolean exitOnStdinClose, List<ProcessHandle> descendants) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitOnStdinClose = exitOnStdinClose;
            this.descendants = descendants;
        }

        static ScriptedProcess exiting(int code, String out, String err) {
            ScriptedProcess p = new ScriptedProcess(out.getBytes(StandardCharsets.UTF_8),
                    err.getBytes(StandardCharsets.UTF_8), false, List.of());
            p.exit.complete(code);
            return p;
        }

        static ScriptedProcess exitingAfterStdinCloses(int code) {
            ScriptedProcess p = new ScriptedProcess(new byte[0], new byte[0], true, List.of());
            p.exitCode = code;
            return p;
        }

        static ScriptedProcess runningUntilKilled(ProcessHandle... descendants) {
            return new ScriptedProcess(new byte[0], new byte[0], false, List.of(descendants));
        }

        static ScriptedProcess printingThenRunning(String out) {
            return new ScriptedProcess(out.getBytes(StandardCharsets.UTF_8), new byte[0], false, List.of());
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int b) {
                    stdin.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    stdin.write(b, off, len);
                }

                @Override
                public void flush() {
                    stdin.flush();
                }

                @Override
                public void close() {
                    stdin.flush();
                    if (exitOnStdinClose) {
                        exit.complete(exitCode);
                    }
                }
            };
        }

        @Override
        public InputStream getInputStream() {
            return streamThatEndsOnExit(stdout);
        }

        @Override
        public InputStream getErrorStream() {
            return streamThatEndsOnExit(stderr);
        }

        private InputStream streamThatEndsOnExit(byte[] bytes) {
            ByteArrayInputStream content = new ByteArrayInputStream(bytes);
            return new InputStream() {
                @Override
                public int read() {
                    int b = content.read();
                    if (b < 0) {
                        exit.join();
                    }
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    int n = content.read(b, off, len);
                    if (n < 0) {
                        exit.join();
                    }
                    return n;
                }
            };
        }

        @Override
        public int waitFor() throws InterruptedException {
            try {
                return exit.get();
            } catch (ExecutionException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public int exitValue() {
            if (!exit.isDone()) {
                throw new IllegalThreadStateException();
            }
            return exit.join();
        }

        @Override
        public boolean isAlive() {
            return !exit.isDone();
        }

        @Override
        public void destroy() {
            exit.complete(143);
        }

        @Override
        public Process destroyForcibly() {
            forciblyDestroyed = true;
            exit.complete(137);
            return this;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return descendants.stream();
        }
    }
}
