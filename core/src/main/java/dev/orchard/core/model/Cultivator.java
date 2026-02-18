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
    Instant createdAt,
    Instant lastActiveAt
) {
    public static Cultivator create(String username, String email) {
        Instant now = Instant.now();
        return new Cultivator(UUID.randomUUID(), username, email, now, now);
    }
}
