package dev.orchard.api.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published after a Bee record has been removed. Carries no state, because the Bee no longer
 * has one; consumers drop the Bee from their view.
 */
public record BeeRemovedEvent(
    UUID beeId,
    UUID groveId,
    Instant removedAt
) {}
