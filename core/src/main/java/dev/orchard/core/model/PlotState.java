package dev.orchard.core.model;

/**
 * Lifecycle of a {@link Plot}. {@link #SAPLING} means the provider has an operational substrate —
 * not "a VM is running" and not "SSH accepts connections".
 */
public enum PlotState {
    GERMINATING,
    SPROUTING,
    SAPLING,
    WILTING,
    WITHERED,
    BLIGHTED
}
