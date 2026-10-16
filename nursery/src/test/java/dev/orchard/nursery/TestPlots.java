package dev.orchard.nursery;

import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.VmPlot;
import dev.orchard.core.model.VmSpec;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Shared factory for throwaway {@link VmPlot} instances in nursery unit tests. */
public final class TestPlots {

    private TestPlots() {}

    /** A SAPLING with random ids, a small spec, and an unroutable address on the default SSH port. */
    public static VmPlot fake() {
        return fake(22);
    }

    public static VmPlot fake(int sshPort) {
        return new VmPlot(UUID.randomUUID(), UUID.randomUUID(), "fake", "i-fake",
            PlotState.SAPLING, Instant.now(), Instant.now(), "10.0.0.1", sshPort, List.of(),
            VmSpec.small());
    }
}
