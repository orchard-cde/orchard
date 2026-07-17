package dev.orchard.api.event;

import dev.orchard.core.model.BeeState;

import java.time.Instant;
import java.util.UUID;

public record BeeStateChangedEvent(
    UUID beeId,
    UUID groveId,
    BeeState previousState,
    BeeState newState,
    Instant changedAt
) {
    public static BeeStateChangedEvent of(
            UUID beeId, UUID groveId,
            BeeState previousState, BeeState newState) {
        return new BeeStateChangedEvent(beeId, groveId, previousState, newState, Instant.now());
    }
}
