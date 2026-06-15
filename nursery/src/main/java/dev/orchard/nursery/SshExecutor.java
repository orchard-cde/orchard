package dev.orchard.nursery;

import dev.orchard.core.model.Seedling;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
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

    public String execute(String command, long timeoutSeconds) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(buildSshCommand(command));

        log.debug("Executing SSH command on seedling {}: {}", seedling.id(), command);
        Process process = pb.start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("SSH command timed out after " + timeoutSeconds + "s: " + command);
        }

        int exitCode = process.exitValue();
        StringBuilder stdout = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stdout.append(line).append("\n");
            }
        }

        if (exitCode != 0) {
            StringBuilder stderr = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(line).append("\n");
                }
            }
            log.error("SSH command failed (exit {}): {} — stderr: {}", exitCode, command, stderr.toString().trim());
            throw new IOException("SSH command failed with exit code " + exitCode + ": " + command);
        }

        return stdout.toString();
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
     * Builds the {@code ssh} argv used by both blocking and streaming execution paths. The two
     * call sites previously duplicated this list verbatim; centralising it ensures any future
     * option additions stay in sync.
     */
    private List<String> buildSshCommand(String remoteCommand) {
        var cmd = new ArrayList<String>();
        cmd.add("ssh");
        cmd.add("-o"); cmd.add("StrictHostKeyChecking=no");
        cmd.add("-o"); cmd.add("UserKnownHostsFile=/dev/null");
        cmd.add("-o"); cmd.add("ConnectTimeout=10");
        java.nio.file.Path orchardKey = resolveSshKeyPath();
        if (java.nio.file.Files.exists(orchardKey)) {
            cmd.add("-i"); cmd.add(orchardKey.toString());
        }
        cmd.add("-p"); cmd.add(String.valueOf(seedling.sshPort()));
        cmd.add("cultivator@" + seedling.ipAddress());
        cmd.add(remoteCommand);
        return cmd;
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
