package dev.orchard.trellis.websocket;

import dev.orchard.api.event.GroveStateChangedEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * WebSocket message payload sent to clients when a grove's state changes.
 * Uses String representations of states for simpler client-side parsing.
 */
public record GroveStateMessage(
    UUID groveId,
    String groveName,
    String previousState,
    String newState,
    Instant changedAt
) {
    public static GroveStateMessage from(GroveStateChangedEvent event) {
        return new GroveStateMessage(
            event.groveId(),
            event.groveName(),
            event.previousState().name(),
            event.newState().name(),
            event.changedAt()
        );
    }
}
