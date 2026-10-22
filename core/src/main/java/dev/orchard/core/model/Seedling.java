package dev.orchard.core.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The VM record a {@link Grove} holds until it moves onto {@link Plot}; {@link VmPlot} is its
 * replacement. Despite the name this describes the plot (where fruit grows), not what is planted:
 * once {@code Grove} holds a {@code Plot}, the {@code Seedling} name passes to what is planted into
 * a plot, described by {@link SeedlingSpec} (#86).
 */
public record Seedling(
    UUID id,
    UUID groveId,
    String providerInstanceId,
    String ipAddress,
    int sshPort,
    SeedlingState state,
    VmSpec spec,
    Instant plantedAt,
    Instant readyAt,
    List<String> authorizedKeys
) {
    public Seedling(
        UUID id,
        UUID groveId,
        String providerInstanceId,
        String ipAddress,
        int sshPort,
        SeedlingState state,
        VmSpec spec,
        Instant plantedAt,
        Instant readyAt
    ) {
        this(id, groveId, providerInstanceId, ipAddress, sshPort, state, spec, plantedAt, readyAt, List.of());
    }

    public static Seedling germinate(UUID groveId, VmSpec spec) {
        return new Seedling(
            UUID.randomUUID(),
            groveId,
            null,
            null,
            22,
            SeedlingState.GERMINATING,
            spec,
            Instant.now(),
            null,
            List.of()
        );
    }

    public Seedling withAuthorizedKeys(List<String> keys) {
        return new Seedling(id, groveId, providerInstanceId, ipAddress, sshPort,
            state, spec, plantedAt, readyAt, keys);
    }

    public Seedling withState(SeedlingState newState) {
        return new Seedling(id, groveId, providerInstanceId, ipAddress, sshPort,
            newState, spec, plantedAt, newState == SeedlingState.SAPLING ? Instant.now() : readyAt, authorizedKeys);
    }

    public Seedling withProviderDetails(String instanceId, String ip) {
        return new Seedling(id, groveId, instanceId, ip, sshPort, state, spec, plantedAt, readyAt, authorizedKeys);
    }

    public Seedling withEndpoint(String instanceId, String host, int port) {
        return new Seedling(id, groveId, instanceId, host, port,
            state, spec, plantedAt, readyAt, authorizedKeys);
    }

    public boolean isReady() {
        return state == SeedlingState.SAPLING;
    }
}
