package dev.orchard.api.dto;

import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.Grove;
import dev.orchard.core.model.GroveState;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GroveResponse(
    UUID id,
    String name,
    String repositoryUrl,
    String branch,
    String commitSha,
    GroveState state,
    String sshConnectionString,
    PlotInfo plot,
    List<FruitInfo> fruits,
    Instant plantedAt,
    Instant lastAccessedAt
) {
    public record FruitInfo(
        UUID id,
        String state,
        String containerId,
        String containerName,
        String serviceName
    ) {}

    /**
     * Returns the primary fruit info (first in the list) for backward compatibility.
     */
    public FruitInfo primaryFruit() {
        return fruits != null && !fruits.isEmpty() ? fruits.getFirst() : null;
    }

    public static GroveResponse fromModel(Grove grove) {
        List<FruitInfo> fruitInfos = List.of();
        if (grove.fruits() != null && !grove.fruits().isEmpty()) {
            fruitInfos = grove.fruits().stream()
                .map(f -> new FruitInfo(
                    f.id(),
                    f.state().name(),
                    f.containerId(),
                    f.containerName(),
                    f.serviceName()
                ))
                .toList();
        }

        return new GroveResponse(
            grove.id(),
            grove.name(),
            grove.repositoryUrl(),
            grove.branch(),
            grove.commitSha(),
            grove.state(),
            grove.getSshConnectionString(),
            grove.plot() == null ? null : PlotInfo.from(grove.plot()),
            fruitInfos,
            grove.plantedAt(),
            grove.lastAccessedAt()
        );
    }
}
