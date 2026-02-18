package dev.orchard.core.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
    List<Fruit> fruits,
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
            List.of(),
            Instant.now(),
            Instant.now()
        );
    }

    /**
     * Returns the primary fruit (first in the list), or null if no fruits exist.
     */
    public Fruit primaryFruit() {
        return fruits != null && !fruits.isEmpty() ? fruits.getFirst() : null;
    }

    public Grove withState(GroveState newState) {
        return new Grove(id, cultivatorId, name, repositoryUrl, branch, commitSha,
            newState, seedling, fruits, plantedAt, Instant.now());
    }

    public Grove withSeedling(Seedling seedling) {
        return new Grove(id, cultivatorId, name, repositoryUrl, branch, commitSha,
            state, seedling, fruits, plantedAt, Instant.now());
    }

    public Grove withFruits(List<Fruit> fruits) {
        return new Grove(id, cultivatorId, name, repositoryUrl, branch, commitSha,
            state, seedling, fruits, plantedAt, Instant.now());
    }

    public Grove withFruit(Fruit fruit) {
        List<Fruit> updated = new ArrayList<>(fruits != null ? fruits : List.of());
        // Replace existing fruit with same id, or add new
        boolean replaced = false;
        for (int i = 0; i < updated.size(); i++) {
            if (updated.get(i).id().equals(fruit.id())) {
                updated.set(i, fruit);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            updated.add(fruit);
        }
        return new Grove(id, cultivatorId, name, repositoryUrl, branch, commitSha,
            state, seedling, List.copyOf(updated), plantedAt, Instant.now());
    }

    public Grove withCommit(String sha) {
        return new Grove(id, cultivatorId, name, repositoryUrl, branch, sha,
            state, seedling, fruits, plantedAt, Instant.now());
    }

    public boolean isReady() {
        return state == GroveState.FLOURISHING
            && seedling != null && seedling.isReady()
            && fruits != null && !fruits.isEmpty()
            && fruits.stream().allMatch(Fruit::isReady);
    }

    public String getSshConnectionString() {
        if (seedling == null || seedling.ipAddress() == null) {
            return null;
        }
        return String.format("ssh -p %d cultivator@%s", seedling.sshPort(), seedling.ipAddress());
    }
}
