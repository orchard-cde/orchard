package dev.orchard.vine;

import dev.orchard.core.model.Seedling;
import dev.orchard.core.model.SeedlingState;

import java.time.Instant;
import java.util.UUID;

/**
 * Shared factory for throwaway {@link Seedling} instances in {@code :vine} unit tests.
 *
 * <p>Deliberately separate from {@code dev.orchard.nursery.TestSeedlings}, which serves the same
 * purpose for {@code :nursery}'s tests: {@code :nursery} depends on {@code :vine}, not the other
 * way around, so {@code :vine} cannot reach into {@code :nursery}'s test sources. Do not merge
 * these two — the cross-module duplication is structural, not drift.
 */
final class VineTestSeedlings {

    private VineTestSeedlings() {}

    /** A SAPLING with random ids, a small spec, and an unroutable address on the given SSH port. */
    static Seedling fake(int sshPort) {
        return new Seedling(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "i-fake",
            "10.0.0.1",
            sshPort,
            SeedlingState.SAPLING,
            Seedling.SeedlingSpec.small(),
            Instant.now(),
            Instant.now()
        );
    }
}
