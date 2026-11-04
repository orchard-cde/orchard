package dev.orchard.vine;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Local runner for container-engine work, and the one place the engine endpoint is applied. A null or
 * blank {@code engineUrl} inherits the process environment; otherwise every command gets
 * {@code DOCKER_HOST} and {@code CONTAINER_HOST} set to it, which also reaches the CLIs the
 * devcontainer CLI starts itself. Only {@code unix://} and {@code tcp://} are accepted; TLS for
 * {@code tcp://} is not supported.
 */
public class ContainerCommandRunner implements CommandRunner {

    private final Map<String, String> engineEnv;
    private final LocalCommandRunner delegate;

    public ContainerCommandRunner(String engineUrl) {
        this.engineEnv = engineEnv(engineUrl);
        this.delegate = new LocalCommandRunner(engineEnv);
    }

    /** The variables every engine command runs with, for callers that start processes themselves. */
    public Map<String, String> engineEnv() {
        return engineEnv;
    }

    @Override
    public String execute(String command) throws IOException, InterruptedException {
        return delegate.execute(command);
    }

    @Override
    public String execute(String command, long timeoutSeconds) throws IOException, InterruptedException {
        return delegate.execute(command, timeoutSeconds);
    }

    @Override
    public void executeStreaming(String command, Consumer<String> lineConsumer, long timeoutSeconds)
            throws IOException, InterruptedException {
        delegate.executeStreaming(command, lineConsumer, timeoutSeconds);
    }

    @Override
    public Optional<String> readFile(String path) {
        return delegate.readFile(path);
    }

    private static Map<String, String> engineEnv(String engineUrl) {
        if (engineUrl == null || engineUrl.isBlank()) {
            return Map.of();
        }
        if (!engineUrl.startsWith("unix://") && !engineUrl.startsWith("tcp://")) {
            throw new IllegalArgumentException(
                "unsupported container engine URL: " + engineUrl + "; use unix:// or tcp://");
        }
        return Map.of("DOCKER_HOST", engineUrl, "CONTAINER_HOST", engineUrl);
    }
}
