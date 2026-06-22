package dev.orchard.trowel.config;

/**
 * Thrown when configuration resolution fails for a reason the user must fix,
 * such as an unknown {@code --target}. Handled at the CLI boundary (see
 * {@code Trowel.createCommandLine}), which prints the message and returns a
 * non-zero exit code rather than terminating the JVM.
 */
public class ConfigException extends RuntimeException {
    public ConfigException(String message) {
        super(message);
    }
}
