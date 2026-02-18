package dev.orchard.core.model;

/**
 * The lifecycle state of a Prebuild (cached workspace image).
 * A prebuild goes through the greenhouse to produce a ready-to-use image.
 */
public enum PrebuildState {
    /**
     * The prebuild is sprouting (image is being built)
     */
    SPROUTING,

    /**
     * The prebuild is ripe (image is built and cached, ready for use)
     */
    RIPE,

    /**
     * The prebuild has been composted (image was superseded by a newer build)
     */
    COMPOSTED,

    /**
     * The prebuild failed (image build encountered an error)
     */
    FAILED
}
