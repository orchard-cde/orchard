package dev.orchard.core.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A Cultivator is a user who tends to Groves in the Orchard.
 * They plant Seeds, grow Seedlings, and harvest Fruit.
 */
public record Cultivator(
    UUID id,
    String username,
    String email,
    String provider,
    String providerId,
    String avatarUrl,
    String displayName,
    Instant createdAt,
    Instant lastActiveAt
) {
    public static Cultivator create(String username, String email) {
        Instant now = Instant.now();
        return new Cultivator(UUID.randomUUID(), username, email, "oidc", null, null, null, now, now);
    }

    public static Cultivator createFromOAuth(String provider, String providerId,
                                              String username, String email,
                                              String avatarUrl, String displayName) {
        Instant now = Instant.now();
        return new Cultivator(UUID.randomUUID(), username, email, provider, providerId,
            avatarUrl, displayName, now, now);
    }
}
