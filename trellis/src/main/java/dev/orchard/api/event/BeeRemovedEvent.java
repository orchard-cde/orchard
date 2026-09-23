package dev.orchard.api.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Carries no state: a removed Bee no longer has one. Consumers drop the Bee from their view.
 */
public record BeeRemovedEvent(
    UUID beeId,
    UUID groveId,
    Instant removedAt
) {}
