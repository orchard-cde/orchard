package dev.orchard.nursery;

import dev.orchard.core.model.Seedling;

import java.util.concurrent.CompletableFuture;

/**
 * Abstraction for VM providers that grow Seedlings.
 * Implementations might include QEMU (local), AWS EC2, GCP Compute, Azure VMs, etc.
 */
public interface SeedlingProvider {

    /**
     * Returns the unique identifier for this provider.
     */
    String getProviderId();

    /**
     * Verifies the devcontainer CLI is installed and matches the expected version on the seedling.
     * Called by each provider before transitioning a Seedling to {@code SAPLING} (READY).
     *
     * <p>The default impl uses {@link SshExecutor}. The package-private overload accepting an
     * explicit {@link CommandRunner} is the test seam — matches the Lane B
     * {@code Function<Seedling, CommandRunner>} pattern used by {@link DevcontainerCli}.
     *
     * @throws SeedlingProvisioningException if the CLI is missing or the version mismatches.
     */
    default void verifyDevcontainerCli(Seedling seedling, String expectedVersion) {
        verifyDevcontainerCli(seedling, expectedVersion, new SshExecutor(seedling));
    }

    /**
     * Test-friendly overload that runs the verification against an explicit {@link CommandRunner}.
     * Production callers use {@link #verifyDevcontainerCli(Seedling, String)} which wraps SSH.
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

    /**
     * Plants a seedling (provisions a VM).
     * The seedling transitions from GERMINATING -> SPROUTING -> SAPLING
     */
    CompletableFuture<Seedling> plant(Seedling seedling);

    /**
     * Waters a seedling (starts a stopped VM).
     */
    CompletableFuture<Seedling> water(Seedling seedling);

    /**
     * Lets a seedling go dormant (stops/suspends a VM without destroying).
     */
    CompletableFuture<Seedling> dormant(Seedling seedling);

    /**
     * Uproots a seedling (terminates and destroys a VM).
     */
    CompletableFuture<Void> uproot(Seedling seedling);

    /**
     * Gets the current state of a seedling from the provider.
     */
    CompletableFuture<Seedling> inspect(Seedling seedling);

    /**
     * Checks if this provider is available and properly configured.
     */
    boolean isAvailable();
}
