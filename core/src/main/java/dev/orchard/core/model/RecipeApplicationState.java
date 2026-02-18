package dev.orchard.core.model;

/**
 * The lifecycle state of a recipe application job.
 * Tracks the progress of applying an OpenRewrite recipe to a Grove's codebase.
 */
public enum RecipeApplicationState {
    /**
     * The recipe application job is queued and waiting to run.
     */
    PENDING,

    /**
     * The recipe is currently being applied to the codebase.
     */
    RUNNING,

    /**
     * The recipe was applied successfully. Changed files and diff are available.
     */
    COMPLETED,

    /**
     * The recipe application failed due to an error.
     */
    FAILED
}
