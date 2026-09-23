package dev.orchard.api.controller;

import dev.orchard.api.event.BeeRemovedEvent;
import dev.orchard.api.event.BeeStateChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Relays bee events onto the grove SSE stream, so clients already watching
 * {@code GET /api/groves/{groveId}/events} see bee changes on the same connection.
 */
@Component
public class BeeEventBroadcaster {

    private final GroveSseRegistry sseRegistry;

    public BeeEventBroadcaster(GroveSseRegistry sseRegistry) {
        this.sseRegistry = sseRegistry;
    }

    @EventListener
    public void onBeeStateChanged(BeeStateChangedEvent event) {
        sseRegistry.broadcast(event.groveId(), "bee-state-changed", Map.of(
            "beeId", event.beeId().toString(),
            "groveId", event.groveId().toString(),
            "previousState", event.previousState().name(),
            "newState", event.newState().name(),
            "changedAt", event.changedAt().toString()
        ));
    }

    @EventListener
    public void onBeeRemoved(BeeRemovedEvent event) {
        sseRegistry.broadcast(event.groveId(), "bee-removed", Map.of(
            "beeId", event.beeId().toString(),
            "groveId", event.groveId().toString(),
            "removedAt", event.removedAt().toString()
        ));
    }
}
