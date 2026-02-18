package dev.orchard.core.model;

/**
 * The lifecycle state of a Grove (workspace).
 */
public enum GroveState {
    /**
     * The grove is being cleared and prepared
     */
    PREPARING,

    /**
     * The grove is being planted (VM + container provisioning)
     */
    PLANTING,

    /**
     * The grove is growing (environment starting up)
     */
    GROWING,

    /**
     * The grove is flourishing and ready for work
     */
    FLOURISHING,

    /**
     * The grove is going dormant (suspending)
     */
    DORMANT,

    /**
     * The grove is being cleared (tearing down)
     */
    CLEARING,

    /**
     * The grove has been cleared (terminated)
     */
    CLEARED,

    /**
     * The grove encountered a blight (error state)
     */
    BLIGHTED
}
