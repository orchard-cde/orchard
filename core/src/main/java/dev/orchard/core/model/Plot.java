package dev.orchard.core.model;

import java.time.Instant;
import java.util.UUID;

/**
 * The place a grove is planted: a VM, or a host workspace plus a container runtime. Sealed so every
 * decision that depends on the substrate is an exhaustive {@code switch} with no {@code default}.
 */
public sealed interface Plot permits VmPlot, ContainerPlot {

    UUID id();

    UUID groveId();

    /** Which provider owns this plot (#228). */
    String providerId();

    /** The provider's own handle: an EC2 instance id, a container id. */
    String providerInstanceId();

    PlotState state();

    Instant plantedAt();

    Instant readyAt();

    Plot withState(PlotState state);
}
