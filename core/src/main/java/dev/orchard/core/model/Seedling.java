package dev.orchard.core.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A Seedling is a VM that hosts Fruit (containers).
 * It grows from GERMINATING through to SAPLING state when fully ready.
 */
public record Seedling(
    UUID id,
    UUID groveId,
    String providerInstanceId,
    String ipAddress,
    int sshPort,
    SeedlingState state,
    SeedlingSpec spec,
    Instant plantedAt,
    Instant readyAt
) {
    public record SeedlingSpec(
        int cpuCores,
        int memoryMb,
        int diskGb,
        String machineType,
        String serialOutput
    ) {
        public static SeedlingSpec small() {
            return new SeedlingSpec(2, 4096, 20, "small", null);
        }

        public static SeedlingSpec medium() {
            return new SeedlingSpec(4, 8192, 40, "medium", null);
        }

        public static SeedlingSpec large() {
            return new SeedlingSpec(8, 16384, 80, "large", null);
        }
    }

    public static Seedling germinate(UUID groveId, SeedlingSpec spec) {
        return new Seedling(
            UUID.randomUUID(),
            groveId,
            null,
            null,
            22,
            SeedlingState.GERMINATING,
            spec,
            Instant.now(),
            null
        );
    }

    public Seedling withState(SeedlingState newState) {
        return new Seedling(id, groveId, providerInstanceId, ipAddress, sshPort,
            newState, spec, plantedAt, newState == SeedlingState.SAPLING ? Instant.now() : readyAt);
    }

    public Seedling withProviderDetails(String instanceId, String ip) {
        return new Seedling(id, groveId, instanceId, ip, sshPort, state, spec, plantedAt, readyAt);
    }

    public boolean isReady() {
        return state == SeedlingState.SAPLING;
    }
}
