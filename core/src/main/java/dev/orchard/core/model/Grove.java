package dev.orchard.core.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A Grove is a complete development workspace in the Orchard.
 * It contains a Seedling (VM) with Fruit (devcontainers) growing on it.
 * Each Grove belongs to a Cultivator and is rooted in a git repository.
 */
public record Grove(
    UUID id,
    UUID cultivatorId,
    String name,
    String repositoryUrl,
    String branch,
    String commitSha,
    GroveState state,
    Seedling seedling,
    Fruit fruit,
    Instant plantedAt,
    Instant lastAccessedAt
) {
    public static Grove plant(UUID cultivatorId, String name, String repositoryUrl, String branch) {
        return new Grove(
            UUID.randomUUID(),
            cultivatorId,
            name,
            repositoryUrl,
            branch,
            null,
            GroveState.PREPARING,
            null,
            null,
            Instant.now(),
            Instant.now()
        );
    }

    public Grove withState(GroveState newState) {
        return new Grove(id, cultivatorId, name, repositoryUrl, branch, commitSha,
            newState, seedling, fruit, plantedAt, Instant.now());
    }

    public Grove withSeedling(Seedling seedling) {
        return new Grove(id, cultivatorId, name, repositoryUrl, branch, commitSha,
            state, seedling, fruit, plantedAt, Instant.now());
    }

    public Grove withFruit(Fruit fruit) {
        return new Grove(id, cultivatorId, name, repositoryUrl, branch, commitSha,
            state, seedling, fruit, plantedAt, Instant.now());
    }

    public Grove withCommit(String sha) {
        return new Grove(id, cultivatorId, name, repositoryUrl, branch, sha,
            state, seedling, fruit, plantedAt, Instant.now());
    }

    public boolean isReady() {
        return state == GroveState.FLOURISHING
            && seedling != null && seedling.isReady()
            && fruit != null && fruit.isReady();
    }

    public String getSshConnectionString() {
        if (seedling == null || seedling.ipAddress() == null) {
            return null;
        }
        return String.format("ssh -p %d cultivator@%s", seedling.sshPort(), seedling.ipAddress());
    }
}
