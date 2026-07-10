package dev.orchard.api.event;

import dev.orchard.core.model.GroveState;

import java.time.Instant;
import java.util.UUID;

/**
 * Event published when a Grove's state changes during provisioning or teardown.
 * Consumed by WebSocket broadcasters and SSE emitters to notify cultivators in real time.
 */
public record GroveStateChangedEvent(
    UUID groveId,
    UUID cultivatorId,
    String groveName,
    GroveState previousState,
    GroveState newState,
    Instant changedAt
) {
    public static GroveStateChangedEvent of(
            UUID groveId,
            UUID cultivatorId,
            String groveName,
            GroveState previousState,
            GroveState newState) {
        return new GroveStateChangedEvent(groveId, cultivatorId, groveName, previousState, newState, Instant.now());
    }
}
