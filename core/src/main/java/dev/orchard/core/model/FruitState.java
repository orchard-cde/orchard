package dev.orchard.core.model;

/**
 * The lifecycle state of a Fruit (devcontainer).
 */
public enum FruitState {
    /**
     * The fruit is forming (container image being built/pulled)
     */
    BUDDING,

    /**
     * The fruit is ripening (container is starting)
     */
    RIPENING,

    /**
     * The fruit is ripe and ready (container is running)
     */
    RIPE,

    /**
     * The fruit is being picked (container is stopping)
     */
    PICKING,

    /**
     * The fruit has been picked (container is stopped)
     */
    PICKED,

    /**
     * The fruit has rotted (container failed)
     */
    ROTTED
}
