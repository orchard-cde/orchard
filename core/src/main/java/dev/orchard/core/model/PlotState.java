package dev.orchard.core.model;

/**
 * Lifecycle of a {@link Plot}. {@link #ROOTED} means the provider has an operational substrate —
 * not "a VM is running" and not "SSH accepts connections". {@link #WILTING} is shutting down and
 * may end {@link #DORMANT} or {@link #WITHERED}. {@link #DORMANT} is powered off with the disk
 * retained and can be watered back to {@link #ROOTED}.
 */
public enum PlotState {
    GERMINATING,
    SPROUTING,
    ROOTED,
    WILTING,
    DORMANT,
    WITHERED,
    BLIGHTED
}
