package dev.orchard.nursery;

import dev.orchard.core.model.Seedling;
import dev.orchard.core.model.SeedlingState;

import java.time.Instant;
import java.util.UUID;

/**
 * Shared factory for throwaway {@link Seedling} instances in nursery unit tests, replacing the
 * near-identical {@code fakeSeedling()}/{@code stubSeedling()} copies that had begun to drift.
 */
public final class TestSeedlings {

    private TestSeedlings() {}

    /** A SAPLING with random ids, a small spec, and an unroutable address on the default SSH port. */
    public static Seedling fake() {
        return fake(22);
    }

    /** As {@link #fake()} but with an explicit SSH port (useful for asserting argv construction). */
    public static Seedling fake(int sshPort) {
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
