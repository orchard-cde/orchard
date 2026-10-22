package dev.orchard.nursery;

import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.Plot;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.SeedlingSpec;
import dev.orchard.vine.ExecTarget;
import dev.orchard.vine.Vine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Shared {@link #plantSubstrate} orchestration for {@link GroveProvider}s: async dispatch, the
 * {@code SAPLING} transition, and failure classification to {@code BLIGHTED}. Subclasses supply
 * the substrate-specific steps.
 *
 * <p>Also carries the behaviour every substrate shares — fruit is grown by the injected
 * {@link FruitGrower} against the {@link Vine} the subclass supplies.
 *
 * <p>Every method narrows its {@link Plot} through {@link #narrow} before any async dispatch, so a
 * plot of the wrong substrate is refused on the calling thread.
 *
 * @param <P> the {@link Plot} subtype this provider operates on
 * @param <L> the provider's own launch-result type, carrying whatever transient state
 *            {@link #launch} needs to hand to the later steps. It is a type parameter rather than
 *            a shared field because concurrent {@code plantSubstrate()} calls would race on a field.
 */
public abstract class AbstractGroveProvider<P extends Plot, L> implements GroveProvider {

    /**
     * Resolved from {@code getClass()} rather than a static field so failure logs are attributed to
     * the concrete provider (e.g. {@code QemuGroveProvider}) rather than to this class. Private,
     * and therefore not inherited — subclasses keep their own logger.
     */
    private final Logger log = LoggerFactory.getLogger(getClass());

    protected final ExecutorService executor;

    private final Class<P> plotType;

    private final FruitGrower fruitGrower;

    protected AbstractGroveProvider(Class<P> plotType, ExecutorService executor, FruitGrower fruitGrower) {
        this.plotType = plotType;
        this.executor = executor;
        this.fruitGrower = fruitGrower;
    }

    @Override
    public final Plot reserve(UUID groveId, SeedlingSpec seedlingSpec) {
        return draft(groveId, seedlingSpec);
    }

    /**
     * Narrows to the only substrate this provider supports.
     *
     * @throws IllegalArgumentException if handed a plot of another substrate
     */
    protected final P narrow(Plot plot) {
        if (!plotType.isInstance(plot)) {
            throw new IllegalArgumentException(
                getProviderId() + " cannot operate on " + plot.getClass().getSimpleName() + " " + plot.id());
        }
        return plotType.cast(plot);
    }

    /**
     * Runs the shared planting sequence. {@code final} on purpose: the async dispatch, the
     * {@code SAPLING} transition, and the failure-to-{@code BLIGHTED} mapping are the contract this
     * class exists to guarantee, and an override would silently forfeit all three.
     *
     * <p>A returned {@code SAPLING} means {@link #ready} completed, i.e. the substrate is usable.
     * It does not mean the devcontainer CLI is installed yet; a VM, for instance, may still be
     * running cloud-init. Verifying the CLI is the caller's responsibility — see
     * {@link GroveProvider#verifyDevcontainerCli}.
     */
    @Override
    public final CompletableFuture<Plot> plantSubstrate(Plot draft) {
        P plot = narrow(draft);
        return CompletableFuture.supplyAsync(() -> {
            L launched = null;
            try {
                launched = launch(plot);
                return ready(plot, launched).withState(PlotState.SAPLING);
            } catch (Exception e) {
                log.error("Failed to plant plot {} via {}{}",
                    plot.id(), getProviderId(), failureContext(launched), e);
                return plot.withState(PlotState.BLIGHTED);
            }
        }, executor);
    }

    /** Builds the unplanted plot for a new seedling. */
    protected abstract P draft(UUID groveId, SeedlingSpec seedlingSpec);

    /** Acquires the substrate. Returns whatever the later steps need to know about it. */
    protected abstract L launch(P plot) throws Exception;

    /**
     * Waits until the launched substrate is usable and returns the launched plot, carrying whatever
     * identity later operations need. Its state is ignored; the base sets {@code SAPLING}.
     */
    protected abstract P ready(P plot, L launched) throws Exception;

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

    /** Returns the vine for the plot. Called on every use; implementations should not assume it is cached. */
    protected abstract Vine vineFor(P plot);

    /** The workspace folder as seen by whatever executes the vine's commands, not necessarily the host. */
    protected abstract String workspacePath(P plot);

    @Override
    public final CompletableFuture<Fruit> growFruit(Plot plot, Fruit fruit) {
        return fruitGrower.grow(execTarget(plot), fruit);
    }

    @Override
    public final CompletableFuture<Void> compostFruit(Plot plot, Fruit fruit) {
        return fruitGrower.compost(execTarget(plot), fruit);
    }

    private ExecTarget execTarget(Plot plot) {
        P narrowed = narrow(plot);
        return new ExecTarget(vineFor(narrowed).commands(), workspacePath(narrowed), narrowed.id());
    }

    @Override
    public final Vine vine(Plot plot) {
        return vineFor(narrow(plot));
    }

    @Override
    public final void verifyDevcontainerCli(Plot plot, String expectedVersion) {
        GroveProvider.verifyDevcontainerCli(plot, expectedVersion, vine(plot).commands());
    }
}
