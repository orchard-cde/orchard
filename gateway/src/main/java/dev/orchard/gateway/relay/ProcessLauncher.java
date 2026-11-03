package dev.orchard.gateway.relay;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Starts a process from an argv list with {@code env} added to the inherited environment. Production uses
 * {@link ProcessBuilder}; tests script the process.
 */
@FunctionalInterface
public interface ProcessLauncher {

    Process launch(List<String> argv, Map<String, String> env) throws IOException;

    static ProcessLauncher processBuilder() {
        return (argv, env) -> {
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.environment().putAll(env);
            return builder.start();
        };
    }
}
