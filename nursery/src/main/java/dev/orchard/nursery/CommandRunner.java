package dev.orchard.nursery;

import java.io.IOException;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Interface for executing commands on a remote target (like a Seedling).
 * Allows for dependency injection of the specific execution mechanism (SSH, API, etc.).
 */
public interface CommandRunner {

    /**
     * Executes a command and returns the buffered stdout. Blocks until the command exits.
     *
     * @param command the remote command
     * @return the standard output from the command
     * @throws IOException if the command exits non-zero or an I/O error occurs
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    String execute(String command) throws IOException, InterruptedException;

    /**
     * Like {@link #execute(String)} but with an explicit timeout (seconds).
     * Default impls delegate to {@code execute(command)} with a fixed timeout; {@link SshExecutor}
     * honours the value.
     *
     * @param command the remote command
     * @param timeoutSeconds process timeout
     * @return the standard output from the command
     * @throws IOException if the command exits non-zero, times out, or an I/O error occurs
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    String execute(String command, long timeoutSeconds) throws IOException, InterruptedException;

    /**
     * Executes a command and streams each stdout line to the given consumer as it arrives.
     * Blocks until the command exits or the timeout elapses. Stderr is drained concurrently
     * (to avoid the child blocking on a full pipe buffer) and surfaced on non-zero exit.
     *
     * <p>Invocations of {@code lineConsumer} are made from a separate thread; if the consumer
     * touches shared mutable state, it is responsible for synchronization.
     *
     * @param command         the remote command
     * @param lineConsumer    invoked with each stdout line (trailing newline stripped)
     * @param timeoutSeconds  process timeout
     * @throws IOException if the command exits non-zero, times out, or the line consumer throws
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    void executeStreaming(String command, Consumer<String> lineConsumer, long timeoutSeconds)
        throws IOException, InterruptedException;

    /**
     * Reads a file from the remote target. Returns empty if the file does not exist.
     */
    Optional<String> readFile(String remotePath);
}
