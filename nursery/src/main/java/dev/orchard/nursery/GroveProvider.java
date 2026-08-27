package dev.orchard.nursery;

import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.Seedling;
import dev.orchard.vine.CommandRunner;
import dev.orchard.vine.Vine;

import java.util.concurrent.CompletableFuture;

/**
 * One provider per substrate, owning every interaction with it: acquiring it, growing fruit on it,
 * tearing it down, and reaching into it.
 *
 * <p>Deliberately granular rather than a single end-to-end {@code plant(Grove, Seed)}. Grove-level
 * orchestration — state transitions, event publishing, persistence — stays in {@code GroveService};
 * moving it here would be a behaviour change rather than the adapter this stage is meant to be. The
 * end-to-end shape is revisited once a second substrate exists to justify it. See issue #86.
 *
 * <p>{@code Seedling} appears in these signatures because VM substrates are the only implementation
 * today. It is provider-internal by design and generalizes to a substrate handle in a later stage;
 * callers must not assume a seedling is SSH-reachable.
 */
public interface GroveProvider {

    String getProviderId();

    boolean isAvailable();

    /** Acquires the substrate. Resolves to SAPLING on success, BLIGHTED on failure. */
    CompletableFuture<Seedling> plantSubstrate(Seedling seedling);

    CompletableFuture<Seedling> water(Seedling seedling);

    CompletableFuture<Seedling> dormant(Seedling seedling);

    CompletableFuture<Void> uproot(Seedling seedling);

    CompletableFuture<Seedling> inspect(Seedling seedling);

    /** Materializes one fruit on an already-planted substrate. */
    CompletableFuture<Fruit> growFruit(Seedling seedling, Fruit fruit);

    CompletableFuture<Void> compostFruit(Seedling seedling, Fruit fruit);

    /**
     * How to reach into this grove. Available before the substrate is routable — provisioning and
     * diagnostic callers depend on that.
     */
    Vine vine(Seedling seedling);

    /**
     * Verifies the devcontainer CLI on the substrate. Called after the substrate is reachable and
     * cloud-init has finished; see issue #148.
     *
     * @throws SeedlingProvisioningException if the CLI is missing or the version mismatches
     */
    void verifyDevcontainerCli(Seedling seedling, String expectedVersion);

    /**
     * Verifies the devcontainer CLI is installed and matches the expected version on the seedling.
     *
     * <p>Called by the orchestrating caller (e.g. {@code GroveService}) after the seedling has
     * already reached {@code SAPLING} and cloud-init has finished — not by the provider during
     * {@link #plantSubstrate}. Cloud-init may still be installing the CLI when
     * {@code plantSubstrate()} returns a SAPLING, so verifying any earlier would race a legitimate
     * in-progress install. See issue #148.
     *
     * <p>{@link #verifyDevcontainerCli(Seedling, String)} delegates here with an explicit
     * {@link CommandRunner} obtained from its {@link #vine}. This static is the sole production
     * path — matches the Lane B {@code Function<Seedling, CommandRunner>} pattern used by
     * {@link DevcontainerCli}.
     *
     * @throws SeedlingProvisioningException if the CLI is missing or the version mismatches.
     */
    static void verifyDevcontainerCli(Seedling seedling, String expectedVersion, CommandRunner runner) {
        try {
            String version = runner.execute("devcontainer --version").trim();
            if (!expectedVersion.equals(version)) {
                throw new SeedlingProvisioningException(
                    "devcontainer CLI version mismatch on seedling " + seedling.id()
                        + ": expected " + expectedVersion + ", got " + version);
            }
        } catch (SeedlingProvisioningException sse) {
            throw sse;
        } catch (Exception e) {
            throw new SeedlingProvisioningException(
                "Seedling " + seedling.id() + " missing devcontainer CLI — check cloud-init logs", e);
        }
    }
}
