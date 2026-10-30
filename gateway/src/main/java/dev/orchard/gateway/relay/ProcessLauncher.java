package dev.orchard.gateway.relay;

import java.io.IOException;
import java.util.List;

/** Starts a process from an argv list. Production uses {@link ProcessBuilder}; tests script the process. */
@FunctionalInterface
public interface ProcessLauncher {

    Process launch(List<String> argv) throws IOException;

    static ProcessLauncher processBuilder() {
        return argv -> new ProcessBuilder(argv).start();
    }
}
