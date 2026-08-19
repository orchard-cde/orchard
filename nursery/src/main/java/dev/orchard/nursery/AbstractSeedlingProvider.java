package dev.orchard.nursery;

import dev.orchard.core.model.Seedling;
import dev.orchard.core.model.SeedlingState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Shared {@link #plant} orchestration for seedling providers: async dispatch, the
 * {@code SAPLING} transition, and failure classification to {@code BLIGHTED}. Subclasses supply
 * the substrate-specific steps.
 *
 * @param <L> the provider's own launch-result type, carrying whatever transient state
 *            {@link #launch} needs to hand to the later steps. It is a type parameter rather than
 *            a shared field because concurrent {@code plant()} calls would race on a field.
 */
public abstract class AbstractSeedlingProvider<L> implements SeedlingProvider {

    /**
     * Resolved from {@code getClass()} rather than a static field so failure logs are attributed to
     * the concrete provider (e.g. {@code QemuSeedlingProvider}) rather than to this class. Private,
     * and therefore not inherited — subclasses keep their own logger.
     */
    private final Logger log = LoggerFactory.getLogger(getClass());

    protected final ExecutorService executor;

    protected AbstractSeedlingProvider(ExecutorService executor) {
        this.executor = executor;
    }

    /**
     * Runs the shared planting sequence. {@code final} on purpose: the async dispatch, the
     * {@code SAPLING} transition, and the failure-to-{@code BLIGHTED} mapping are the contract this
     * class exists to guarantee, and an override would silently forfeit all three.
     *
     * <p>A substrate that genuinely cannot express itself as these steps should implement
     * {@link SeedlingProvider} directly rather than extending this class — that is the escape hatch,
     * and it keeps the guarantee intact for every provider that does extend.
     *
     * <p>A returned {@code SAPLING} means the substrate is reachable (e.g. SSH accepts connections);
     * it does not mean the devcontainer CLI is installed yet, since cloud-init may still be running.
     * Verifying the CLI is the caller's responsibility, performed after cloud-init completes — see
     * {@link SeedlingProvider#verifyDevcontainerCli}.
     */
    @Override
    public final CompletableFuture<Seedling> plant(Seedling seedling) {
        return CompletableFuture.supplyAsync(() -> {
            L launched = null;
            try {
                launched = launch(seedling);
                awaitRunning(seedling, launched);
                PlantedSeedling planted = resolveEndpoint(seedling, launched);
                awaitReachable(seedling, planted);
                return seedling
                    .withEndpoint(planted.providerInstanceId(), planted.host(), planted.sshPort())
                    .withState(SeedlingState.SAPLING);
            } catch (Exception e) {
                log.error("Failed to plant seedling {} via {}{}",
                    seedling.id(), getProviderId(), failureContext(launched), e);
                return seedling.withState(SeedlingState.BLIGHTED);
            }
        }, executor);
    }

    /** Acquires the substrate. Returns whatever the later steps need to know about it. */
    protected abstract L launch(Seedling seedling) throws Exception;

    /**
     * Waits until the substrate reports itself running. Defaults to a no-op for substrates whose
     * {@link #launch} call already returns a live workload (e.g. a local process).
     */
    protected void awaitRunning(Seedling seedling, L launched) throws Exception {
    }

    /** Resolves the provider-side identity and reachable address of the launched substrate. */
    protected abstract PlantedSeedling resolveEndpoint(Seedling seedling, L launched) throws Exception;

    /** Blocks until the resolved endpoint accepts connections. */
    protected abstract void awaitReachable(Seedling seedling, PlantedSeedling planted) throws Exception;

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
}
