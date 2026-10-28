package dev.orchard.nursery;

import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.SeedlingSpec;
import dev.orchard.core.model.VmEndpoint;
import dev.orchard.core.model.VmPlot;
import dev.orchard.vine.SshVine;
import dev.orchard.vine.Vine;

import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * VM layer over {@link AbstractGroveProvider}: VM plots, reached over SSH, with the workspace at
 * {@code /workspace}.
 *
 * @param <L> the provider's own launch-result type; see {@link AbstractGroveProvider}
 */
public abstract class AbstractVmGroveProvider<L> extends AbstractGroveProvider<VmPlot, L> {

    protected AbstractVmGroveProvider(ExecutorService executor, FruitGrower fruitGrower) {
        super(VmPlot.class, executor, fruitGrower);
    }

    @Override
    protected final VmPlot draft(UUID groveId, SeedlingSpec seedlingSpec) {
        return VmPlot.germinate(groveId, getProviderId(), seedlingSpec);
    }

    @Override
    protected final VmPlot ready(VmPlot plot, L launched) throws Exception {
        awaitRunning(plot, launched);
        VmEndpoint endpoint = resolveEndpoint(plot, launched);
        awaitReachable(plot, endpoint);
        return plot.withEndpoint(endpoint);
    }

    /**
     * Waits until the substrate reports itself running. Defaults to a no-op for substrates whose
     * {@link #launch} call already returns a live workload (e.g. a local process).
     */
    protected void awaitRunning(VmPlot plot, L launched) throws Exception {
    }

    /** Resolves the provider-side identity and reachable address of the launched substrate. */
    protected abstract VmEndpoint resolveEndpoint(VmPlot plot, L launched) throws Exception;

    /** Blocks until the resolved endpoint accepts connections. */
    protected abstract void awaitReachable(VmPlot plot, VmEndpoint endpoint) throws Exception;

    /**
     * A fresh {@link SshVine} per call, deliberately: it holds the plot's endpoint, which changes
     * across water/inspect cycles, so caching one would hand out a stale address.
     */
    @Override
    protected Vine vineFor(VmPlot plot) {
        return new SshVine(plot);
    }

    protected static void requireDormant(VmPlot vm) {
        if (vm.state() != PlotState.DORMANT) {
            throw new IllegalStateException(
                "Only a DORMANT plot can be watered; plot " + vm.id() + " is " + vm.state());
        }
    }

    @Override
    protected final String workspacePath(VmPlot plot) {
        return "/workspace";
    }
}
