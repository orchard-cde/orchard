package dev.orchard.api.dto;

import dev.orchard.core.model.Cultivator;

import java.time.Instant;
import java.util.UUID;

public record CultivatorResponse(
    UUID id,
    String username,
    String email,
    String provider,
    String avatarUrl,
    String displayName,
    Instant createdAt,
    Instant lastActiveAt
) {
    public static CultivatorResponse fromModel(Cultivator cultivator) {
        return new CultivatorResponse(
            cultivator.id(),
            cultivator.username(),
            cultivator.email(),
            cultivator.provider(),
            cultivator.avatarUrl(),
            cultivator.displayName(),
            cultivator.createdAt(),
            cultivator.lastActiveAt()
        );
    }
}
