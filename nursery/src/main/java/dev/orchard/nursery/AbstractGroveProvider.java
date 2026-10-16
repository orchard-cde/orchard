package dev.orchard.nursery;

import dev.orchard.core.model.ContainerPlot;
import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.Plot;
import dev.orchard.core.model.PlotRequest;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.VmEndpoint;
import dev.orchard.core.model.VmPlot;
import dev.orchard.vine.ExecTarget;
import dev.orchard.vine.SshVine;
import dev.orchard.vine.Vine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Shared {@link #plantSubstrate} orchestration for VM-backed {@link GroveProvider}s: async dispatch,
 * the {@code SAPLING} transition, and failure classification to {@code BLIGHTED}. Subclasses supply
 * the substrate-specific steps.
 *
 * <p>Also carries the behaviour every VM substrate shares — fruit is grown by the injected
 * {@link FruitGrower}, and the grove is reached over SSH.
 *
 * <p>This is the template for VM substrates only. Every method narrows its {@link Plot} through
 * {@link #requireVm} before any async dispatch, so a container plot is refused on the calling
 * thread. A container provider implements {@link GroveProvider} directly.
 *
 * @param <L> the provider's own launch-result type, carrying whatever transient state
 *            {@link #launch} needs to hand to the later steps. It is a type parameter rather than
 *            a shared field because concurrent {@code plantSubstrate()} calls would race on a field.
 */
public abstract class AbstractGroveProvider<L> implements GroveProvider {

    /**
     * Resolved from {@code getClass()} rather than a static field so failure logs are attributed to
     * the concrete provider (e.g. {@code QemuGroveProvider}) rather than to this class. Private,
     * and therefore not inherited — subclasses keep their own logger.
     */
    private final Logger log = LoggerFactory.getLogger(getClass());

    protected final ExecutorService executor;

    private final FruitGrower fruitGrower;

    protected AbstractGroveProvider(ExecutorService executor, FruitGrower fruitGrower) {
        this.executor = executor;
        this.fruitGrower = fruitGrower;
    }

    @Override
    public Plot reserve(UUID groveId, PlotRequest request) {
        return VmPlot.germinate(groveId, getProviderId(), request);
    }

    /**
     * Narrows to the only substrate this template supports.
     *
     * @throws IllegalArgumentException if handed a container plot
     */
    protected final VmPlot requireVm(Plot plot) {
        return switch (plot) {
            case VmPlot vm -> vm;
            case ContainerPlot container -> throw new IllegalArgumentException(
                getProviderId() + " is a VM provider and cannot operate on container plot " + container.id());
        };
    }

    /**
     * Runs the shared planting sequence. {@code final} on purpose: the async dispatch, the
     * {@code SAPLING} transition, and the failure-to-{@code BLIGHTED} mapping are the contract this
     * class exists to guarantee, and an override would silently forfeit all three.
     *
     * <p>A substrate that genuinely cannot express itself as these steps should implement
     * {@link GroveProvider} directly rather than extending this class — that is the escape hatch,
     * and it keeps the guarantee intact for every provider that does extend.
     *
     * <p>A returned {@code SAPLING} means the substrate is reachable (e.g. SSH accepts connections);
     * it does not mean the devcontainer CLI is installed yet, since cloud-init may still be running.
     * Verifying the CLI is the caller's responsibility, performed after cloud-init completes — see
     * {@link GroveProvider#verifyDevcontainerCli}.
     */
    @Override
    public final CompletableFuture<Plot> plantSubstrate(Plot draft) {
        VmPlot vm = requireVm(draft);
        return CompletableFuture.supplyAsync(() -> {
            L launched = null;
            try {
                launched = launch(vm);
                awaitRunning(vm, launched);
                VmEndpoint endpoint = resolveEndpoint(vm, launched);
                awaitReachable(vm, endpoint);
                return vm.withEndpoint(endpoint).withState(PlotState.SAPLING);
            } catch (Exception e) {
                log.error("Failed to plant plot {} via {}{}",
                    vm.id(), getProviderId(), failureContext(launched), e);
                return vm.withState(PlotState.BLIGHTED);
            }
        }, executor);
    }

    /** Acquires the substrate. Returns whatever the later steps need to know about it. */
    protected abstract L launch(VmPlot plot) throws Exception;

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
     * Substrate identifiers to append to a planting-failure log line. A failure after
     * {@link #launch} succeeded may have leaked a real resource, so whatever identifies it must
     * reach the log — for EC2 that is the instance id an operator needs in order to terminate an
     * orphaned instance. The default renders the launch result, which is why launch results should
     * have a useful {@code toString}.
     *
     * @param launched the launch result, or {@code null} if {@link #launch} itself failed
     */
    protected String failureContext(L launched) {
        return launched == null ? "" : " (" + launched + ")";
    }

    @Override
    public CompletableFuture<Fruit> growFruit(Plot plot, Fruit fruit) {
        VmPlot vm = requireVm(plot);
        return fruitGrower.grow(new ExecTarget(vine(vm).commands(), "/workspace", vm.id()), fruit);
    }

    @Override
    public CompletableFuture<Void> compostFruit(Plot plot, Fruit fruit) {
        VmPlot vm = requireVm(plot);
        return fruitGrower.compost(new ExecTarget(vine(vm).commands(), "/workspace", vm.id()), fruit);
    }

    /**
     * A fresh {@link SshVine} per call, deliberately: it holds the plot's endpoint, which changes
     * across water/inspect cycles, so caching one would hand out a stale address.
     */
    @Override
    public Vine vine(Plot plot) {
        return new SshVine(requireVm(plot));
    }

    @Override
    public void verifyDevcontainerCli(Plot plot, String expectedVersion) {
        GroveProvider.verifyDevcontainerCli(plot, expectedVersion, vine(plot).commands());
    }
}
