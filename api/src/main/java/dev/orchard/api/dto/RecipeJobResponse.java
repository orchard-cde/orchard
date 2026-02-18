package dev.orchard.api.dto;

import dev.orchard.core.model.RecipeApplicationJob;
import dev.orchard.core.model.RecipeApplicationState;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API response for a recipe application job.
 */
public record RecipeJobResponse(
    UUID id,
    UUID groveId,
    String recipeId,
    RecipeApplicationState state,
    List<String> changedFiles,
    String diff,
    Instant createdAt,
    Instant completedAt
) {
    public static RecipeJobResponse fromModel(RecipeApplicationJob job) {
        return new RecipeJobResponse(
            job.id(),
            job.groveId(),
            job.recipeId(),
            job.state(),
            job.changedFiles(),
            job.diff(),
            job.createdAt(),
            job.completedAt()
        );
    }
}
