package dev.orchard.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
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
    @JsonIgnore List<String> authorizedKeys,
    VmSpec spec
) implements Plot {

    public VmPlot {
        Objects.requireNonNull(providerId, "providerId");
        authorizedKeys = authorizedKeys == null ? List.of() : authorizedKeys;
    }

    /** A {@code GERMINATING} plot with nothing acquired. */
    public static VmPlot germinate(UUID groveId, String providerId, SeedlingSpec seedlingSpec) {
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
            new VmSpec(seedlingSpec.cpuCores(), seedlingSpec.memoryMb(), seedlingSpec.diskGb(),
                seedlingSpec.machineType(), seedlingSpec.serialOutput())
        );
    }

    @Override
    public VmPlot withState(PlotState newState) {
        return new VmPlot(id, groveId, providerId, providerInstanceId, newState, plantedAt,
            newState == PlotState.ROOTED ? Instant.now() : readyAt,
            host, sshPort, authorizedKeys, spec);
    }

    public VmPlot withAuthorizedKeys(List<String> keys) {
        return new VmPlot(id, groveId, providerId, providerInstanceId, state, plantedAt, readyAt,
            host, sshPort, keys, spec);
    }

    public VmPlot withEndpoint(VmEndpoint endpoint) {
        return new VmPlot(id, groveId, providerId, endpoint.handle().value(), state, plantedAt,
            readyAt, endpoint.host().value(), endpoint.sshPort(), authorizedKeys, spec);
    }
}
