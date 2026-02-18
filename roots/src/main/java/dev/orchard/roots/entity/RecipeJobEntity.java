package dev.orchard.roots.entity;

import dev.orchard.core.model.RecipeApplicationJob;
import dev.orchard.core.model.RecipeApplicationState;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "recipe_jobs")
public class RecipeJobEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID groveId;

    @Column(nullable = false)
    private String recipeId;

    @Column(nullable = false)
    private String state;

    @Column(columnDefinition = "TEXT")
    private String changedFiles;

    @Column(columnDefinition = "TEXT")
    private String diff;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant completedAt;

    protected RecipeJobEntity() {}

    public static RecipeJobEntity fromModel(RecipeApplicationJob job) {
        RecipeJobEntity entity = new RecipeJobEntity();
        entity.id = job.id();
        entity.groveId = job.groveId();
        entity.recipeId = job.recipeId();
        entity.state = job.state().name();
        entity.changedFiles = job.changedFiles() != null && !job.changedFiles().isEmpty()
            ? String.join("\n", job.changedFiles())
            : null;
        entity.diff = job.diff();
        entity.createdAt = job.createdAt();
        entity.completedAt = job.completedAt();
        return entity;
    }

    public RecipeApplicationJob toModel() {
        List<String> files = changedFiles != null && !changedFiles.isBlank()
            ? Arrays.stream(changedFiles.split("\n"))
                .filter(s -> !s.isBlank())
                .toList()
            : List.of();

        return new RecipeApplicationJob(
            id,
            groveId,
            recipeId,
            RecipeApplicationState.valueOf(state),
            files,
            diff,
            createdAt,
            completedAt
        );
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getGroveId() { return groveId; }
    public String getRecipeId() { return recipeId; }
    public String getState() { return state; }
    public String getChangedFiles() { return changedFiles; }
    public String getDiff() { return diff; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }

    // Setters for state updates
    public void setState(String state) { this.state = state; }
    public void setChangedFiles(String changedFiles) { this.changedFiles = changedFiles; }
    public void setDiff(String diff) { this.diff = diff; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
