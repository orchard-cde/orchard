package dev.orchard.roots.entity;

import dev.orchard.core.model.Prebuild;
import dev.orchard.core.model.PrebuildState;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "prebuilds")
public class PrebuildEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String repositoryUrl;

    @Column(nullable = false)
    private String branch;

    private String commitSha;

    private String imageRef;

    @Column(nullable = false)
    private String state;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant completedAt;

    protected PrebuildEntity() {}

    public static PrebuildEntity fromModel(Prebuild prebuild) {
        PrebuildEntity entity = new PrebuildEntity();
        entity.id = prebuild.id();
        entity.repositoryUrl = prebuild.repositoryUrl();
        entity.branch = prebuild.branch();
        entity.commitSha = prebuild.commitSha();
        entity.imageRef = prebuild.imageRef();
        entity.state = prebuild.state().name();
        entity.createdAt = prebuild.createdAt();
        entity.completedAt = prebuild.completedAt();
        return entity;
    }

    public Prebuild toModel() {
        return new Prebuild(
            id,
            repositoryUrl,
            branch,
            commitSha,
            imageRef,
            PrebuildState.valueOf(state),
            createdAt,
            completedAt
        );
    }

    // Getters
    public UUID getId() { return id; }
    public String getRepositoryUrl() { return repositoryUrl; }
    public String getBranch() { return branch; }
    public String getCommitSha() { return commitSha; }
    public String getImageRef() { return imageRef; }
    public String getState() { return state; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }

    // Setters for state updates
    public void setState(String state) { this.state = state; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }
    public void setImageRef(String imageRef) { this.imageRef = imageRef; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
