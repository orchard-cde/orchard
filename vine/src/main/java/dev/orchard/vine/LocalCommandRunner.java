package dev.orchard.vine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Runs commands on the trellis host itself through {@code /bin/sh -c}. A descendant that detaches from
 * the shell before it exits is not killed on timeout or interrupt.
 */
public final class LocalCommandRunner implements CommandRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalCommandRunner.class);
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;
    private static final long DRAIN_GRACE_MS = 2_000L;
    private static final int STDERR_CAPTURE_CAP_BYTES = 64 * 1024;

    public LocalCommandRunner() {}

    @Override
    public String execute(String command) throws IOException, InterruptedException {
        return execute(command, DEFAULT_TIMEOUT_SECONDS);
    }

    @Override
    public String execute(String command, long timeoutSeconds) throws IOException, InterruptedException {
        log.debug("Executing local command: {}", command);
        Process process = start(command);

        StringBuffer stdout = new StringBuffer();
        Thread stdoutReader = Thread.ofVirtual().name("local-exec-stdout")
            .start(() -> drain(process.getInputStream(), stdout, Integer.MAX_VALUE));
        StringBuffer stderr = new StringBuffer();
        Thread stderrReader = Thread.ofVirtual().name("local-exec-stderr")
            .start(() -> drain(process.getErrorStream(), stderr, STDERR_CAPTURE_CAP_BYTES));

        awaitExit(process, timeoutSeconds, command, stdoutReader, stderrReader);

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw failure(exitCode, command, stderr);
        }
        return stdout.toString();
    }

    @Override
    public void executeStreaming(String command, Consumer<String> lineConsumer, long timeoutSeconds)
            throws IOException, InterruptedException {
        log.debug("Streaming local command: {}", command);
        Process process = start(command);

        AtomicReference<Throwable> consumerFailure = new AtomicReference<>();
        Thread stdoutReader = Thread.ofVirtual().name("local-stream-stdout").start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        lineConsumer.accept(line);
                    } catch (Throwable t) {
                        consumerFailure.set(t);
                        destroyTree(process);
                        return;
                    }
                }
            } catch (IOException e) {
                log.warn("Local stdout reader hit IO error: {}", e.getMessage());
            }
        });
        StringBuffer stderr = new StringBuffer();
        Thread stderrReader = Thread.ofVirtual().name("local-stream-stderr")
            .start(() -> drain(process.getErrorStream(), stderr, STDERR_CAPTURE_CAP_BYTES));

        awaitExit(process, timeoutSeconds, command, stdoutReader, stderrReader);

        Throwable consumerThrowable = consumerFailure.get();
        if (consumerThrowable != null) {
            throw new IOException("Streaming line consumer failed", consumerThrowable);
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw failure(exitCode, command, stderr);
        }
    }

    @Override
    public Optional<String> readFile(String path) {
        try {
            String content = execute("cat " + ShellQuote.quote(path) + " 2>/dev/null");
            return content.isEmpty() ? Optional.empty() : Optional.of(content);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("File not found or unreadable locally: {}", path);
            return Optional.empty();
        }
    }

    private static Process start(String command) throws IOException {
        return new ProcessBuilder("/bin/sh", "-c", command).start();
    }

    /**
     * Waits for exit, then gives the readers a bounded grace to drain. The process tree is destroyed on
     * timeout, interrupt, or any other abnormal exit; a grandchild that outlives the shell and holds a
     * pipe open is abandoned after the grace rather than waited on.
     */
    private static void awaitExit(Process process, long timeoutSeconds, String command,
                                  Thread stdoutReader, Thread stderrReader)
            throws IOException, InterruptedException {
        boolean exited = false;
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                destroyTree(process);
                stdoutReader.join(DRAIN_GRACE_MS);
                stderrReader.join(DRAIN_GRACE_MS);
                throw new IOException("Local command timed out after " + timeoutSeconds + "s: " + command);
            }
            exited = true;
            stdoutReader.join(DRAIN_GRACE_MS);
            stderrReader.join(DRAIN_GRACE_MS);
            if (stdoutReader.isAlive() || stderrReader.isAlive()) {
                destroyTree(process);
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
            }
        } finally {
            if (!exited) {
                destroyTree(process);
            }
        }
    }

    private static void destroyTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // best effort: releasing a reader stuck on an inherited pipe
        }
    }

    private static IOException failure(int exitCode, String command, StringBuffer stderr) {
        String tail = stderr.toString().trim();
        log.error("Local command failed (exit {}): {} - stderr: {}", exitCode, command, tail);
        return new IOException("Local command failed with exit code " + exitCode + ": " + command
            + (tail.isEmpty() ? "" : " - " + tail));
    }

    private static void drain(InputStream in, StringBuffer sink, int capBytes) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sink.length() < capBytes) {
                    sink.append(line).append("\n");
                }
            }
        } catch (IOException e) {
            log.warn("Local reader hit IO error: {}", e.getMessage());
        }
    }
}
