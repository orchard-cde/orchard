package dev.orchard.api.dto;

import dev.orchard.core.model.Grove;
import dev.orchard.core.model.GroveState;

import java.time.Instant;
import java.util.UUID;

public record GroveResponse(
    UUID id,
    String name,
    String repositoryUrl,
    String branch,
    String commitSha,
    GroveState state,
    String sshConnectionString,
    SeedlingInfo seedling,
    FruitInfo fruit,
    Instant plantedAt,
    Instant lastAccessedAt
) {
    public record SeedlingInfo(
        UUID id,
        String state,
        String ipAddress,
        int sshPort,
        int cpuCores,
        int memoryMb,
        int diskGb
    ) {}

    public record FruitInfo(
        UUID id,
        String state,
        String containerId,
        String containerName
    ) {}

    public static GroveResponse fromModel(Grove grove) {
        SeedlingInfo seedlingInfo = null;
        if (grove.seedling() != null) {
            var s = grove.seedling();
            seedlingInfo = new SeedlingInfo(
                s.id(),
                s.state().name(),
                s.ipAddress(),
                s.sshPort(),
                s.spec().cpuCores(),
                s.spec().memoryMb(),
                s.spec().diskGb()
            );
        }

        FruitInfo fruitInfo = null;
        if (grove.fruit() != null) {
            var f = grove.fruit();
            fruitInfo = new FruitInfo(
                f.id(),
                f.state().name(),
                f.containerId(),
                f.containerName()
            );
        }

        return new GroveResponse(
            grove.id(),
            grove.name(),
            grove.repositoryUrl(),
            grove.branch(),
            grove.commitSha(),
            grove.state(),
            grove.getSshConnectionString(),
            seedlingInfo,
            fruitInfo,
            grove.plantedAt(),
            grove.lastAccessedAt()
        );
    }
}
