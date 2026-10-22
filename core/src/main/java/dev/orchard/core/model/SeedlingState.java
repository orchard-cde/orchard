package dev.orchard.core.model;

/**
 * The lifecycle state of the VM record {@link Seedling}. {@link PlotState} has the same constants and
 * replaces it once {@code Grove} holds a {@link Plot}.
 */
public enum SeedlingState {
    /**
     * The seedling is being prepared (VM provisioning requested)
     */
    GERMINATING,

    /**
     * The seedling is sprouting (VM is starting up)
     */
    SPROUTING,

    /**
     * The seedling has grown into a sapling (VM is running and ready)
     */
    SAPLING,

    /**
     * The seedling is wilting (VM is shutting down)
     */
    WILTING,

    /**
     * The seedling has withered (VM is stopped/terminated)
     */
    WITHERED,

    /**
     * The seedling failed to grow (VM provisioning failed)
     */
    BLIGHTED
}
