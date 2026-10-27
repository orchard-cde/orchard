package dev.orchard.core.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.UUID;

/**
 * The place a grove is planted: a VM, or a host workspace plus a container runtime. Sealed so every
 * decision that depends on the substrate is an exhaustive {@code switch} with no {@code default}.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "@type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = VmPlot.class, name = "vm"),
    @JsonSubTypes.Type(value = ContainerPlot.class, name = "container")
})
public sealed interface Plot permits VmPlot, ContainerPlot {

    UUID id();

    UUID groveId();

    /**
     * The id of the provider this plot was handed to; never null.
     */
    String providerId();

    /** The provider's own handle: an EC2 instance id, a container id. */
    String providerInstanceId();

    PlotState state();

    Instant plantedAt();

    Instant readyAt();

    Plot withState(PlotState state);
}
