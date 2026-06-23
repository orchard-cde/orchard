package dev.orchard.core.model;

import java.util.List;
import java.util.Map;

/**
 * Abstract base for all devcontainer seed specifications.
 * Contains format-neutral fields common to any workspace type.
 */
public abstract class Seed {

    private final String name;
    private final String image;
    private final List<String> forwardPorts;
    private final Map<String, String> containerEnv;

    protected Seed(String name, String image, List<String> forwardPorts, Map<String, String> containerEnv) {
        this.name = name;
        this.image = image;
        this.forwardPorts = forwardPorts != null ? forwardPorts : List.of();
        this.containerEnv = containerEnv != null ? containerEnv : Map.of();
    }

    public String name() { return name; }
    public String image() { return image; }
    public List<String> forwardPorts() { return forwardPorts; }
    public Map<String, String> containerEnv() { return containerEnv; }

    /** Convenience factory that creates a {@link DevcontainerSeed.Builder}. */
    public static DevcontainerSeed.Builder builder() {
        return new DevcontainerSeed.Builder();
    }
}
