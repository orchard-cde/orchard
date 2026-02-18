package dev.orchard.api.dto;

import dev.orchard.core.model.Prebuild;
import dev.orchard.core.model.PrebuildState;

import java.time.Instant;
import java.util.UUID;

/**
 * API response for a prebuild.
 */
public record PrebuildResponse(
    UUID id,
    String repositoryUrl,
    String branch,
    String commitSha,
    String imageRef,
    PrebuildState state,
    Instant createdAt,
    Instant completedAt
) {
    public static PrebuildResponse fromModel(Prebuild prebuild) {
        return new PrebuildResponse(
            prebuild.id(),
            prebuild.repositoryUrl(),
            prebuild.branch(),
            prebuild.commitSha(),
            prebuild.imageRef(),
            prebuild.state(),
            prebuild.createdAt(),
            prebuild.completedAt()
        );
    }
}
