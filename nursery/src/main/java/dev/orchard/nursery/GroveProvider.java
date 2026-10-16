package dev.orchard.nursery;

import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.Plot;
import dev.orchard.core.model.PlotRequest;
import dev.orchard.vine.CommandRunner;
import dev.orchard.vine.Vine;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * One provider per substrate, owning every interaction with it: acquiring it, growing fruit on it,
 * tearing it down, and reaching into it.
 *
 * <p>Deliberately granular rather than a single end-to-end {@code plant(Grove, Seed)}. Grove-level
 * orchestration — state transitions, event publishing, persistence — stays in {@code GroveService};
 * moving it here would change behaviour rather than just adapt the substrate. The
 * end-to-end shape is revisited once a second substrate exists to justify it. See issue #86.
 *
 * <p>{@link Plot} is sealed. A provider that supports only one substrate narrows the plot it is
 * handed and refuses the others with {@link IllegalArgumentException}.
 */
public interface GroveProvider {

    String getProviderId();

    boolean isAvailable();

    /**
     * Stamps a new {@code GERMINATING} plot with a fresh id and this provider's id. Synchronous and
     * acquires nothing; {@link #plantSubstrate} acquires the substrate.
     */
    Plot reserve(UUID groveId, PlotRequest request);

    /** Acquires the substrate. Resolves to SAPLING on success, BLIGHTED on failure. */
    CompletableFuture<Plot> plantSubstrate(Plot draft);

    CompletableFuture<Plot> water(Plot plot);

    CompletableFuture<Plot> dormant(Plot plot);

    /**
     * Releases the substrate (e.g. terminates the underlying VM/instance).
     *
     * <p>The returned future completes exceptionally if the substrate could not be released.
     * Callers must treat that as "the resource may still exist" — never assume the substrate is
     * gone just because this method was called.
     */
    CompletableFuture<Void> uproot(Plot plot);

    CompletableFuture<Plot> inspect(Plot plot);

    /** Materializes one fruit on an already-planted substrate. */
    CompletableFuture<Fruit> growFruit(Plot plot, Fruit fruit);

    /**
     * Removes one fruit's container from an already-planted substrate.
     *
     * <p>The returned future completes exceptionally if the container could not be removed.
     * Callers must treat that as "the resource may still exist" — never assume the container is
     * gone just because this method was called.
     */
    CompletableFuture<Void> compostFruit(Plot plot, Fruit fruit);

    /**
     * How to reach into this grove. Available before the substrate is routable — provisioning and
     * diagnostic callers depend on that.
     */
    Vine vine(Plot plot);

    /**
     * Verifies the devcontainer CLI on the substrate. Called after the substrate is reachable and
     * cloud-init has finished; see issue #148.
     *
     * @throws SeedlingProvisioningException if the CLI is missing or the version mismatches
     */
    void verifyDevcontainerCli(Plot plot, String expectedVersion);

    /**
     * Verifies the devcontainer CLI is installed and matches the expected version on the plot.
     *
     * <p>Called by the orchestrating caller (e.g. {@code GroveService}) after the plot has
     * already reached {@code SAPLING} and cloud-init has finished — not by the provider during
     * {@link #plantSubstrate}. Cloud-init may still be installing the CLI when
     * {@code plantSubstrate()} returns a SAPLING, so verifying any earlier would race a legitimate
     * in-progress install. See issue #148.
     *
     * <p>{@link #verifyDevcontainerCli(Plot, String)} delegates here with an explicit
     * {@link CommandRunner} obtained from its {@link #vine}. This static is the sole production
     * path — it takes the {@link CommandRunner} as a plain per-call argument, the same seam
     * {@link DevcontainerCli} uses.
     *
     * @throws SeedlingProvisioningException if the CLI is missing or the version mismatches.
     */
    static void verifyDevcontainerCli(Plot plot, String expectedVersion, CommandRunner runner) {
        try {
            String version = runner.execute("devcontainer --version").trim();
            if (!expectedVersion.equals(version)) {
                throw new SeedlingProvisioningException(
                    "devcontainer CLI version mismatch on plot " + plot.id()
                        + ": expected " + expectedVersion + ", got " + version);
            }
        } catch (SeedlingProvisioningException sse) {
            throw sse;
        } catch (Exception e) {
            throw new SeedlingProvisioningException(
                "Plot " + plot.id() + " missing devcontainer CLI — check cloud-init logs", e);
        }
    }
}
