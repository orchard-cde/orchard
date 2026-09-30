package dev.orchard.api.controller;

import dev.orchard.api.event.GroveStateChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

/**
 * SSE endpoint for grove activity, for clients that cannot use WebSocket/STOMP.
 * <p>
 * GET /api/groves/{id}/events emits {@code grove-state-changed} on grove transitions and
 * {@code bee-state-changed} / {@code bee-removed} from {@link BeeEventBroadcaster}.
 */
@RestController
@RequestMapping("/api/groves")
public class GroveEventController {

    private final GroveSseRegistry sseRegistry;

    public GroveEventController(GroveSseRegistry sseRegistry) {
        this.sseRegistry = sseRegistry;
    }

    @GetMapping(value = "/{groveId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamGroveEvents(@PathVariable UUID groveId) {
        return sseRegistry.subscribe(groveId);
    }

    @EventListener
    public void onGroveStateChanged(GroveStateChangedEvent event) {
        sseRegistry.broadcast(event.groveId(), "grove-state-changed", Map.of(
            "groveId", event.groveId().toString(),
            "groveName", event.groveName(),
            "previousState", event.previousState().name(),
            "newState", event.newState().name(),
            "changedAt", event.changedAt().toString()
        ));
    }
}
