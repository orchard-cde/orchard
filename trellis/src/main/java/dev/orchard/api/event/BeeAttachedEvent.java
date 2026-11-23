package dev.orchard.api.event;

import dev.orchard.core.model.BeeState;
import dev.orchard.core.model.BeeType;

import java.time.Instant;
import java.util.UUID;

/**
 * Carries only what a client needs to show the new Bee. The spec's config is left out on
 * purpose: {@code attachBee} merges resolved credentials into it.
 */
public record BeeAttachedEvent(
    UUID beeId,
    UUID groveId,
    BeeType beeType,
    String version,
    BeeState state,
    Instant attachedAt
) {}
