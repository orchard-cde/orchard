package dev.orchard.core.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A Fruit is a running devcontainer, grown from a Seed on a Seedling (VM).
 * It represents the actual development environment the Cultivator works in.
 */
public record Fruit(
    UUID id,
    UUID groveId,
    UUID seedlingId,
    String containerId,
    String containerName,
    Seed seed,
    FruitState state,
    List<PortMapping> portMappings,
    Instant buddedAt,
    Instant ripenedAt
) {
    public record PortMapping(
        int containerPort,
        int hostPort,
        String protocol
    ) {}

    public static Fruit bud(UUID groveId, UUID seedlingId, Seed seed) {
        return new Fruit(
            UUID.randomUUID(),
            groveId,
            seedlingId,
            null,
            seed.name() != null ? seed.name() : "orchard-fruit",
            seed,
            FruitState.BUDDING,
            List.of(),
            Instant.now(),
            null
        );
    }

    public Fruit withState(FruitState newState) {
        return new Fruit(id, groveId, seedlingId, containerId, containerName, seed,
            newState, portMappings, buddedAt, newState == FruitState.RIPE ? Instant.now() : ripenedAt);
    }

    public Fruit withContainerDetails(String containerId, List<PortMapping> ports) {
        return new Fruit(id, groveId, seedlingId, containerId, containerName, seed,
            state, ports, buddedAt, ripenedAt);
    }

    public boolean isReady() {
        return state == FruitState.RIPE;
    }
}
