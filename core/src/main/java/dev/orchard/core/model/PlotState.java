package dev.orchard.core.model;

/**
 * Lifecycle of a {@link Plot}. {@link #SAPLING} means the provider has an operational substrate —
 * not "a VM is running" and not "SSH accepts connections". Same constants as {@link SeedlingState},
 * which it is intended to replace.
 */
public enum PlotState {
    GERMINATING,
    SPROUTING,
    SAPLING,
    WILTING,
    WITHERED,
    BLIGHTED
}
