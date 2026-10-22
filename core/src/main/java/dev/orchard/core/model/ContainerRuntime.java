package dev.orchard.core.model;

/**
 * The container CLI a plot's fruit was created with. Names the CLI invoked, not the daemon behind
 * it: a {@code docker} that is podman's compatibility shim is {@link #DOCKER}. Persisted so teardown
 * uses the runtime that created the container, even if the host's resolution has since changed.
 */
public enum ContainerRuntime {
    DOCKER("docker"),
    PODMAN("podman");

    private final String cli;

    ContainerRuntime(String cli) {
        this.cli = cli;
    }

    public String cli() {
        return cli;
    }
}
