package dev.orchard.nursery;

import java.io.IOException;
import java.util.Optional;

/**
 * Interface for executing commands on a remote target (like a Seedling).
 * Allows for dependency injection of the specific execution mechanism (SSH, API, etc.).
 */
public interface CommandRunner {
    
    /**
     * Executes a command on the remote target and returns stdout.
     *
     * @param command the command to execute remotely
     * @return the standard output from the command
     * @throws IOException if the command exits with a non-zero code or an I/O error occurs
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    String execute(String command) throws IOException, InterruptedException;

    /**
     * Reads a file from the remote target. Returns empty if the file does not exist.
     *
     * @param remotePath the absolute path of the file on the remote target
     * @return an Optional containing the file contents, or empty if the file does not exist
     */
    Optional<String> readFile(String remotePath);
}