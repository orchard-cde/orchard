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
