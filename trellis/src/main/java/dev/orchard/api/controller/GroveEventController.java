package dev.orchard.api.controller;

import dev.orchard.api.event.GroveStateChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE (Server-Sent Events) endpoint for grove state changes.
 * Provides a fallback for clients that cannot use WebSocket/STOMP.
 * <p>
 * Usage: GET /api/groves/{id}/events
 * Returns an SSE stream that emits GroveStateChangedEvent payloads
 * whenever the specified grove transitions to a new state.
 */
@RestController
@RequestMapping("/api/groves")
public class GroveEventController {

    private static final Logger log = LoggerFactory.getLogger(GroveEventController.class);
    private static final long SSE_TIMEOUT = 30 * 60 * 1000L; // 30 minutes

    private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    @GetMapping(value = "/{groveId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamGroveEvents(@PathVariable UUID groveId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        emitters.computeIfAbsent(groveId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable removeEmitter = () -> {
            CopyOnWriteArrayList<SseEmitter> groveEmitters = emitters.get(groveId);
            if (groveEmitters != null) {
                groveEmitters.remove(emitter);
                if (groveEmitters.isEmpty()) {
                    emitters.remove(groveId);
                }
            }
        };

        emitter.onCompletion(removeEmitter);
        emitter.onTimeout(removeEmitter);
        emitter.onError(e -> {
            log.debug("SSE emitter error for grove {}: {}", groveId, e.getMessage());
            removeEmitter.run();
        });

        log.debug("New SSE subscriber for grove {}", groveId);
        return emitter;
    }

    @EventListener
    public void onGroveStateChanged(GroveStateChangedEvent event) {
        CopyOnWriteArrayList<SseEmitter> groveEmitters = emitters.get(event.groveId());
        if (groveEmitters == null || groveEmitters.isEmpty()) {
            return;
        }

        Map<String, Object> payload = Map.of(
            "groveId", event.groveId().toString(),
            "groveName", event.groveName(),
            "previousState", event.previousState().name(),
            "newState", event.newState().name(),
            "changedAt", event.changedAt().toString()
        );

        for (SseEmitter emitter : groveEmitters) {
            try {
                emitter.send(SseEmitter.event()
                    .name("grove-state-changed")
                    .data(payload));
            } catch (IOException e) {
                log.debug("Failed to send SSE event to subscriber for grove {}", event.groveId());
                groveEmitters.remove(emitter);
            }
        }
    }
}
