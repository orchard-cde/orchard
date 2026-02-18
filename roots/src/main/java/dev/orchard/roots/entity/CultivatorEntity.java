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
    private String provider;

    @Column(name = "provider_id")
    private String providerId;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "display_name")
    private String displayName;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastActiveAt;

    protected CultivatorEntity() {}

    public CultivatorEntity(UUID id, String username, String email, String provider,
                            String providerId, String avatarUrl, String displayName,
                            Instant createdAt, Instant lastActiveAt) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.provider = provider;
        this.providerId = providerId;
        this.avatarUrl = avatarUrl;
        this.displayName = displayName;
        this.createdAt = createdAt;
        this.lastActiveAt = lastActiveAt;
    }

    public static CultivatorEntity fromModel(Cultivator cultivator) {
        return new CultivatorEntity(
            cultivator.id(),
            cultivator.username(),
            cultivator.email(),
            cultivator.provider(),
            cultivator.providerId(),
            cultivator.avatarUrl(),
            cultivator.displayName(),
            cultivator.createdAt(),
            cultivator.lastActiveAt()
        );
    }

    public Cultivator toModel() {
        return new Cultivator(id, username, email, provider, providerId, avatarUrl, displayName,
            createdAt, lastActiveAt);
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getProvider() { return provider; }
    public String getProviderId() { return providerId; }
    public String getAvatarUrl() { return avatarUrl; }
    public String getDisplayName() { return displayName; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastActiveAt() { return lastActiveAt; }

    public void setLastActiveAt(Instant lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
