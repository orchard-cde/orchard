package dev.orchard.nursery.container;

import dev.orchard.core.model.ContainerRuntime;
import dev.orchard.vine.CommandRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;

public final class ContainerRuntimes {

    private static final Logger log = LoggerFactory.getLogger(ContainerRuntimes.class);

    private static final long PROBE_TIMEOUT_SECONDS = 30;

    private ContainerRuntimes() {}

    /**
     * Prefers the podman CLI whenever a Podman engine is reachable, even through a {@code docker}
     * CLI pointed at it: the Docker-compat API can pull the wrong architecture variant.
     */
    public static Optional<ContainerRuntime> resolve(CommandRunner local) throws InterruptedException {
        boolean dockerUp = succeeds(local, "docker info");
        if (dockerUp && !dockerFrontsPodman(local)) {
            return Optional.of(ContainerRuntime.DOCKER);
        }
        if (succeeds(local, "podman info")) {
            return Optional.of(ContainerRuntime.PODMAN);
        }
        return dockerUp ? Optional.of(ContainerRuntime.DOCKER) : Optional.empty();
    }

    private static boolean dockerFrontsPodman(CommandRunner local) throws InterruptedException {
        try {
            return local.execute("docker version --format '{{json .Server.Components}}'", PROBE_TIMEOUT_SECONDS)
                .contains("Podman Engine");
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean succeeds(CommandRunner local, String command) throws InterruptedException {
        try {
            local.execute(command, PROBE_TIMEOUT_SECONDS);
            return true;
        } catch (IOException e) {
            log.debug("{} failed: {}", command, e.getMessage());
            return false;
        }
    }
}
