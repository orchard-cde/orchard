package dev.orchard.core.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record VmPlot(
    UUID id,
    UUID groveId,
    String providerId,
    String providerInstanceId,
    PlotState state,
    Instant plantedAt,
    Instant readyAt,
    String host,
    int sshPort,
    List<String> authorizedKeys,
    VmSpec spec
) implements Plot {

    /** A {@code GERMINATING} plot with nothing acquired. Mirrors {@link Seedling#germinate}. */
    public static VmPlot germinate(UUID groveId, String providerId, PlotRequest request) {
        return new VmPlot(
            UUID.randomUUID(),
            groveId,
            providerId,
            null,
            PlotState.GERMINATING,
            Instant.now(),
            null,
            null,
            22,
            List.of(),
            new VmSpec(request.cpuCores(), request.memoryMb(), request.diskGb(),
                request.machineType(), request.serialOutput())
        );
    }

    @Override
    public VmPlot withState(PlotState newState) {
        return new VmPlot(id, groveId, providerId, providerInstanceId, newState, plantedAt,
            newState == PlotState.SAPLING ? Instant.now() : readyAt,
            host, sshPort, authorizedKeys, spec);
    }

    public VmPlot withEndpoint(VmEndpoint endpoint) {
        return new VmPlot(id, groveId, providerId, endpoint.instanceId().value(), state, plantedAt,
            readyAt, endpoint.host().value(), endpoint.sshPort(), authorizedKeys, spec);
    }
}
