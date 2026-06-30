package dev.orchard.nursery;

import dev.orchard.core.model.Seedling;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes commands on a Seedling (VM) over SSH.
 * Shared utility for any component that needs to run remote commands.
 */
public class SshExecutor implements CommandRunner {

    private static final Logger log = LoggerFactory.getLogger(SshExecutor.class);
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;
    /** Grace period for the stdout reader thread to drain remaining buffered output after the process exits or is killed. */
    private static final long STDOUT_DRAIN_GRACE_MS = 2_000L;
    /** Cap on retained stderr bytes — devcontainer up is verbose and we only need a tail for error context. */
    private static final int STDERR_CAPTURE_CAP_BYTES = 64 * 1024;

    private final Seedling seedling;

    public SshExecutor(Seedling seedling) {
        this.seedling = seedling;
    }

    /**
     * Executes a command on the seedling via SSH and returns stdout.
     *
     * @param command the command to execute remotely
     * @return the standard output from the command
     * @throws IOException if the command exits with a non-zero code or an I/O error occurs
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public String execute(String command) throws IOException, InterruptedException {
        return execute(command, DEFAULT_TIMEOUT_SECONDS);
    }

    @Override
    public String execute(String command, long timeoutSeconds) throws IOException, InterruptedException {
        log.debug("Executing SSH command on seedling {}: {}", seedling.id(), command);
        Process process = new ProcessBuilder(buildSshCommand(command)).start();

        // Drain stdout AND stderr on parallel virtual threads while the process runs. Reading only
        // after waitFor() returns deadlocks any command that emits more than the OS pipe buffer
        // (~64KB) — the ssh child blocks writing, we block in waitFor(), nothing drains. readFile()
        // (cat of an arbitrary remote file) can easily exceed that, so the drain must be concurrent.
        StringBuilder stdout = new StringBuilder();
        Thread stdoutReader = Thread.ofVirtual().name("ssh-exec-stdout")
            .start(() -> drain(process.getInputStream(), stdout, Integer.MAX_VALUE));
        StringBuilder stderr = new StringBuilder();
        Thread stderrReader = Thread.ofVirtual().name("ssh-exec-stderr")
            .start(() -> drain(process.getErrorStream(), stderr, STDERR_CAPTURE_CAP_BYTES));

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            stdoutReader.join(STDOUT_DRAIN_GRACE_MS);
            stderrReader.join(STDOUT_DRAIN_GRACE_MS);
            throw new IOException("SSH command timed out after " + timeoutSeconds + "s: " + command);
        }
        // join() before reading the StringBuilders establishes happens-before on their contents.
        stdoutReader.join();
        stderrReader.join();

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            log.error("SSH command failed (exit {}): {} — stderr: {}", exitCode, command, stderr.toString().trim());
            throw new IOException("SSH command failed with exit code " + exitCode + ": " + command);
        }

        return stdout.toString();
    }

    /** Reads {@code in} line-by-line into {@code sink}, retaining at most {@code capBytes} characters. */
    private void drain(java.io.InputStream in, StringBuilder sink, int capBytes) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sink.length() < capBytes) {
                    sink.append(line).append("\n");
                }
                // Past the cap, keep reading to keep the pipe drained but discard the overflow.
            }
        } catch (IOException e) {
            log.warn("SSH reader hit IO error on seedling {}: {}", seedling.id(), e.getMessage());
        }
    }

    @Override
    public void executeStreaming(String command, java.util.function.Consumer<String> lineConsumer, long timeoutSeconds)
            throws IOException, InterruptedException {
        log.debug("Streaming SSH command on seedling {}: {}", seedling.id(), command);
        Process process = new ProcessBuilder(buildSshCommand(command)).start();

        // Captured if lineConsumer.accept(...) throws — surfaced to the caller after the reader joins
        // so a buggy consumer can't silently truncate the stream.
        AtomicReference<Throwable> consumerFailure = new AtomicReference<>();

        // Read stdout incrementally on a virtual thread so we can wait for the process
        // and consume lines without deadlocking on full pipe buffers.
        Thread stdoutReader = Thread.ofVirtual().name("ssh-stream-stdout").start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        lineConsumer.accept(line);
                    } catch (Throwable t) {
                        consumerFailure.set(t);
                        return;
                    }
                }
            } catch (IOException e) {
                log.warn("Streaming stdout reader hit IO error on seedling {}: {}", seedling.id(), e.getMessage());
            }
        });

        // Drain stderr on a parallel virtual thread — devcontainer up can emit >64KB to stderr and
        // would otherwise block on a full pipe buffer, causing waitFor() to spuriously time out.
        StringBuilder stderrCapture = new StringBuilder();
        Thread stderrReader = Thread.ofVirtual().name("ssh-stream-stderr").start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (stderrCapture.length() < STDERR_CAPTURE_CAP_BYTES) {
                        stderrCapture.append(line).append("\n");
                    }
                    // Once over the cap, keep reading (to keep the pipe drained) but discard.
                }
            } catch (IOException e) {
                log.warn("Streaming stderr reader hit IO error on seedling {}: {}", seedling.id(), e.getMessage());
            }
        });

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            stdoutReader.join(STDOUT_DRAIN_GRACE_MS);
            stderrReader.join(STDOUT_DRAIN_GRACE_MS);
            throw new IOException("SSH streaming command timed out after " + timeoutSeconds + "s: " + command);
        }
        stdoutReader.join();
        stderrReader.join();

        Throwable consumerThrowable = consumerFailure.get();
        if (consumerThrowable != null) {
            throw new IOException("Streaming line consumer failed", consumerThrowable);
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            log.error("Streaming SSH command failed (exit {}): {} — stderr: {}",
                exitCode, command, stderrCapture.toString().trim());
            throw new IOException("SSH streaming command failed with exit code " + exitCode + ": " + command);
        }
    }

    /**
     * Builds the {@code ssh} argv used by both blocking and streaming execution paths via the
     * shared {@link SshCommandBuilder} (the single source of truth for connection and liveness
     * options — see that class for the {@code ServerAlive*} liveness semantics).
     *
     * <p>Package-private rather than private so {@code SshExecutorCommandTest} can assert the
     * argv without spinning up a real ssh process.
     */
    List<String> buildSshCommand(String remoteCommand) {
        return new SshCommandBuilder()
            .host(seedling.ipAddress())
            .port(seedling.sshPort())
            .identityKey(resolveSshKeyPath())
            .remoteCommand(remoteCommand)
            .build();
    }

    static java.nio.file.Path resolveSshKeyPath() {
        String keyPath = System.getProperty("orchard.ssh.key-path");
        if (keyPath != null && !keyPath.isBlank()) {
            return java.nio.file.Path.of(keyPath);
        }
        return java.nio.file.Path.of(System.getProperty("user.home"), ".ssh", "orchard_ed25519");
    }

    /**
     * Reads a file from the seedling via SSH. Returns empty if the file does not exist.
     *
     * @param remotePath the absolute path of the file on the seedling
     * @return the file contents, or empty if the file does not exist
     */
    public Optional<String> readFile(String remotePath) {
        try {
            String content = execute("cat " + remotePath + " 2>/dev/null");
            if (content.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(content);
        } catch (IOException | InterruptedException e) {
            log.debug("File not found or unreadable on seedling {}: {}", seedling.id(), remotePath);
            return Optional.empty();
        }
    }
}
