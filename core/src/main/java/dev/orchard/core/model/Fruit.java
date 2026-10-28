package dev.orchard.core.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A Fruit is a running devcontainer, grown from a Seed on a plot.
 * It represents the actual development environment the Cultivator works in.
 */
public record Fruit(
    UUID id,
    UUID groveId,
    UUID plotId,
    String containerId,
    String containerName,
    String serviceName,
    String composeProjectName,
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

    public static Fruit bud(UUID groveId, UUID plotId, Seed seed) {
        return bud(groveId, plotId, seed, null);
    }

    public static Fruit bud(UUID groveId, UUID plotId, Seed seed, String serviceName) {
        return new Fruit(
            UUID.randomUUID(),
            groveId,
            plotId,
            null,
            seed.name() != null ? seed.name() : "orchard-fruit",
            serviceName,
            null,
            seed,
            FruitState.BUDDING,
            List.of(),
            Instant.now(),
            null
        );
    }

    public Fruit withState(FruitState newState) {
        return new Fruit(id, groveId, plotId, containerId, containerName, serviceName, composeProjectName, seed,
            newState, portMappings, buddedAt, newState == FruitState.RIPE ? Instant.now() : ripenedAt);
    }

    public Fruit withContainerDetails(String containerId, List<PortMapping> ports) {
        return new Fruit(id, groveId, plotId, containerId, containerName, serviceName, composeProjectName, seed,
            state, ports, buddedAt, ripenedAt);
    }

    public Fruit withComposeProjectName(String name) {
        return new Fruit(id, groveId, plotId, containerId, containerName, serviceName, name, seed,
            state, portMappings, buddedAt, ripenedAt);
    }

    public boolean isReady() {
        return state == FruitState.RIPE;
    }
}
