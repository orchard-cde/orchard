package dev.orchard.nursery;

import dev.orchard.core.model.Seedling;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Optional;

/**
 * Executes commands on a Seedling (VM) over SSH.
 * Shared utility for any component that needs to run remote commands.
 */
public class SshExecutor {

    private static final Logger log = LoggerFactory.getLogger(SshExecutor.class);

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
        var cmd = new java.util.ArrayList<String>();
        cmd.add("ssh");
        cmd.add("-o"); cmd.add("StrictHostKeyChecking=no");
        cmd.add("-o"); cmd.add("UserKnownHostsFile=/dev/null");
        cmd.add("-o"); cmd.add("ConnectTimeout=10");
        // Use the Orchard SSH key if it exists
        java.nio.file.Path orchardKey = java.nio.file.Path.of(
            System.getProperty("user.home"), ".ssh", "orchard_ed25519");
        if (java.nio.file.Files.exists(orchardKey)) {
            cmd.add("-i"); cmd.add(orchardKey.toString());
        }
        cmd.add("-p"); cmd.add(String.valueOf(seedling.sshPort()));
        cmd.add("cultivator@" + seedling.ipAddress());
        cmd.add(command);
        ProcessBuilder pb = new ProcessBuilder(cmd);

        log.debug("Executing SSH command on seedling {}: {}", seedling.id(), command);
        Process process = pb.start();

        StringBuilder stdout = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stdout.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
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
