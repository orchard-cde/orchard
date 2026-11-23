package dev.orchard.core.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A container substrate: a bind-mounted host workspace and the runtime that owns its fruit.
 *
 * @param remoteUser the primary fruit's remote user, which commands into the grove run as; null
 *                   until the fruit has grown, or when the devcontainer names none
 */
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
    ContainerSpec spec,
    String remoteUser
) implements Plot {

    public ContainerPlot {
        Objects.requireNonNull(providerId, "providerId");
    }

    @Override
    public ContainerPlot withState(PlotState newState) {
        return new ContainerPlot(id, groveId, providerId, providerInstanceId, newState, plantedAt,
            newState == PlotState.ROOTED ? Instant.now() : readyAt,
            workspacePath, runtime, spec, remoteUser);
    }

    public ContainerPlot withRemoteUser(String newRemoteUser) {
        return new ContainerPlot(id, groveId, providerId, providerInstanceId, state, plantedAt, readyAt,
            workspacePath, runtime, spec, newRemoteUser);
    }
}
