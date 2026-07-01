package dev.orchard.core.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Map;

/**
 * Abstract base for all devcontainer seed specifications.
 * Contains format-neutral fields common to any workspace type.
 *
 * <p>Carries Jackson type-info metadata (annotations only — see {@code core}'s
 * dependency on {@code jackson-annotations}) so persisted seeds round-trip
 * polymorphically. {@code defaultImpl} maps pre-discriminator rows (no {@code @type})
 * to {@link DevcontainerSeed} for backward compatibility.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "@type",
    defaultImpl = DevcontainerSeed.class)
@JsonSubTypes({
    @JsonSubTypes.Type(value = DevcontainerSeed.class, name = "devcontainer"),
    @JsonSubTypes.Type(value = DevfileSeed.class, name = "devfile")
})
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
    public static DevcontainerSeed.Builder devcontainer() {
        return new DevcontainerSeed.Builder();
    }

    /** Convenience factory that creates a {@link DevfileSeed.Builder}. */
    public static DevfileSeed.Builder devfile() {
        return new DevfileSeed.Builder();
    }
}
