package dev.orchard.core.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Tracks the application of an OpenRewrite recipe to a Grove's codebase.
 * Created when a Cultivator requests a recipe be applied, and updated as
 * the recipe runs and produces results (changed files, diff output).
 */
public record RecipeApplicationJob(
    UUID id,
    UUID groveId,
    String recipeId,
    RecipeApplicationState state,
    List<String> changedFiles,
    String diff,
    Instant createdAt,
    Instant completedAt
) {
    /**
     * Creates a new recipe application job in PENDING state.
     */
    public static RecipeApplicationJob create(UUID groveId, String recipeId) {
        return new RecipeApplicationJob(
            UUID.randomUUID(),
            groveId,
            recipeId,
            RecipeApplicationState.PENDING,
            List.of(),
            null,
            Instant.now(),
            null
        );
    }

    public RecipeApplicationJob withState(RecipeApplicationState newState) {
        return new RecipeApplicationJob(id, groveId, recipeId, newState, changedFiles, diff,
            createdAt, newState == RecipeApplicationState.COMPLETED || newState == RecipeApplicationState.FAILED
                ? Instant.now() : completedAt);
    }

    public RecipeApplicationJob withResults(List<String> changedFiles, String diff) {
        return new RecipeApplicationJob(id, groveId, recipeId, state, changedFiles, diff,
            createdAt, completedAt);
    }
}
