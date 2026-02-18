package dev.orchard.core.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A Prebuild is a cached workspace image grown in the Greenhouse.
 * When a Cultivator plants a Grove for a repository that has a ripe Prebuild,
 * the pre-built image is used instead of building from scratch, dramatically
 * reducing startup time.
 */
public record Prebuild(
    UUID id,
    String repositoryUrl,
    String branch,
    String commitSha,
    String imageRef,
    PrebuildState state,
    Instant createdAt,
    Instant completedAt
) {
    /**
     * Creates a new Prebuild in SPROUTING state for a given repository and branch.
     */
    public static Prebuild create(String repositoryUrl, String branch) {
        return new Prebuild(
            UUID.randomUUID(),
            repositoryUrl,
            branch,
            null,
            null,
            PrebuildState.SPROUTING,
            Instant.now(),
            null
        );
    }

    public Prebuild withState(PrebuildState newState) {
        return new Prebuild(id, repositoryUrl, branch, commitSha, imageRef,
            newState, createdAt, newState == PrebuildState.RIPE ? Instant.now() : completedAt);
    }

    public Prebuild withCommitSha(String commitSha) {
        return new Prebuild(id, repositoryUrl, branch, commitSha, imageRef,
            state, createdAt, completedAt);
    }

    public Prebuild withImageRef(String imageRef) {
        return new Prebuild(id, repositoryUrl, branch, commitSha, imageRef,
            state, createdAt, completedAt);
    }

    public boolean isUsable() {
        return state == PrebuildState.RIPE && imageRef != null;
    }
}
