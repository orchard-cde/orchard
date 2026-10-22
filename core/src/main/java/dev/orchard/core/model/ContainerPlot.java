package dev.orchard.core.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/** A container substrate: a bind-mounted host workspace and the runtime that owns its fruit. */
public record ContainerPlot(
    UUID id,
    UUID groveId,
    String providerId,
    String providerInstanceId,
    PlotState state,
    Instant plantedAt,
    Instant readyAt,
    Path workspacePath,
    ContainerRuntime runtime,
    ContainerSpec spec
) implements Plot {

    @Override
    public ContainerPlot withState(PlotState newState) {
        return new ContainerPlot(id, groveId, providerId, providerInstanceId, newState, plantedAt,
            newState == PlotState.SAPLING ? Instant.now() : readyAt,
            workspacePath, runtime, spec);
    }
}
