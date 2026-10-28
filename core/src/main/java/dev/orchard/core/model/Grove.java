package dev.orchard.core.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A Grove is a complete development workspace in the Orchard.
 * It is planted in a {@link Plot}, with Fruit (devcontainers) growing on it.
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
    SeedSpec seedSpec,
    Plot plot,
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
            SeedSpec.AUTO,
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
            newState, seedSpec, plot, fruits, plantedAt, Instant.now());
    }

    public Grove withPlot(Plot plot) {
        return new Grove(id, cultivatorId, name, repositoryUrl, branch, commitSha,
            state, seedSpec, plot, fruits, plantedAt, Instant.now());
    }

    public Grove withSeedSpec(SeedSpec seedSpec) {
        return new Grove(id, cultivatorId, name, repositoryUrl, branch, commitSha,
            state, seedSpec, plot, fruits, plantedAt, Instant.now());
    }

    public Grove withFruits(List<Fruit> fruits) {
        return new Grove(id, cultivatorId, name, repositoryUrl, branch, commitSha,
            state, seedSpec, plot, fruits, plantedAt, Instant.now());
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
            state, seedSpec, plot, List.copyOf(updated), plantedAt, Instant.now());
    }

    public Grove withCommit(String sha) {
        return new Grove(id, cultivatorId, name, repositoryUrl, branch, sha,
            state, seedSpec, plot, fruits, plantedAt, Instant.now());
    }

    public boolean isReady() {
        return state == GroveState.FLOURISHING
            && plot != null && plot.state() == PlotState.ROOTED
            && fruits != null && !fruits.isEmpty()
            && fruits.stream().allMatch(Fruit::isReady);
    }

    public Optional<VmPlot> sshReachableVm() {
        return plot instanceof VmPlot vm && vm.host() != null && vm.state() == PlotState.ROOTED
            ? Optional.of(vm)
            : Optional.empty();
    }

    public String getSshConnectionString() {
        VmPlot vm = sshReachableVm().orElse(null);
        if (vm == null) {
            return null;
        }
        String keyPath = resolveSshKeyPath();
        return "ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i %s -p %d cultivator@%s".formatted(
            keyPath, vm.sshPort(), vm.host());
    }

    private static String resolveSshKeyPath() {
        String keyPath = System.getProperty("orchard.ssh.key-path");
        if (keyPath != null && !keyPath.isBlank()) {
            return keyPath;
        }
        return "%s/.ssh/orchard_ed25519".formatted(System.getProperty("user.home"));
    }
}
