package dev.orchard.roots.entity;

import dev.orchard.core.model.Cultivator;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cultivators")
public class CultivatorEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastActiveAt;

    protected CultivatorEntity() {}

    public CultivatorEntity(UUID id, String username, String email, Instant createdAt, Instant lastActiveAt) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.createdAt = createdAt;
        this.lastActiveAt = lastActiveAt;
    }

    public static CultivatorEntity fromModel(Cultivator cultivator) {
        return new CultivatorEntity(
            cultivator.id(),
            cultivator.username(),
            cultivator.email(),
            cultivator.createdAt(),
            cultivator.lastActiveAt()
        );
    }

    public Cultivator toModel() {
        return new Cultivator(id, username, email, createdAt, lastActiveAt);
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastActiveAt() { return lastActiveAt; }

    public void setLastActiveAt(Instant lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }
}
