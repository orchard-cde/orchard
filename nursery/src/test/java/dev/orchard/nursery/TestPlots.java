package dev.orchard.nursery;

import dev.orchard.core.model.DevcontainerSeed;
import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.Plot;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.VmPlot;
import dev.orchard.core.model.VmSpec;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Shared factory for throwaway {@link VmPlot} instances in nursery unit tests. */
public final class TestPlots {

    private TestPlots() {}

    /** A ROOTED with random ids, a small spec, and an unroutable address on the default SSH port. */
    public static VmPlot fake() {
        return fake(22);
    }

    public static VmPlot fake(int sshPort) {
        return new VmPlot(UUID.randomUUID(), UUID.randomUUID(), "fake", "i-fake",
            PlotState.ROOTED, Instant.now(), Instant.now(), "10.0.0.1", sshPort, List.of(),
            VmSpec.small());
    }

    /** A fruit budded on {@code plot}. The seed must be non-null: {@code Fruit.bud} dereferences its name. */
    public static Fruit budded(Plot plot) {
        return Fruit.bud(plot.groveId(), plot.id(),
            DevcontainerSeed.builder().name("test").image("alpine:3").build());
    }
}
