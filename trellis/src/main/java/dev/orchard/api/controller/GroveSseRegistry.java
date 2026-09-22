package dev.orchard.api.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds the live SSE subscribers of {@code GET /api/groves/{groveId}/events}, keyed by grove,
 * so every component pushing onto that stream reaches the same connections.
 */
@Component
public class GroveSseRegistry {

    private static final Logger log = LoggerFactory.getLogger(GroveSseRegistry.class);
    private static final long SSE_TIMEOUT = 30 * 60 * 1000L; // 30 minutes

    private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID groveId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        register(groveId, emitter);
        log.debug("New SSE subscriber for grove {}", groveId);
        return emitter;
    }

    void register(UUID groveId, SseEmitter emitter) {
        emitters.computeIfAbsent(groveId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable removeEmitter = () -> remove(groveId, emitter);
        emitter.onCompletion(removeEmitter);
        emitter.onTimeout(removeEmitter);
        emitter.onError(e -> {
            log.debug("SSE emitter error for grove {}: {}", groveId, e.getMessage());
            removeEmitter.run();
        });
    }

    public void broadcast(UUID groveId, String eventName, Object payload) {
        CopyOnWriteArrayList<SseEmitter> groveEmitters = emitters.get(groveId);
        if (groveEmitters == null || groveEmitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : groveEmitters) {
            try {
                emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(payload));
            } catch (IOException e) {
                log.debug("Subscriber for grove {} went away before event {}", groveId, eventName);
                remove(groveId, emitter);
            }
        }
    }

    int subscriberCount(UUID groveId) {
        CopyOnWriteArrayList<SseEmitter> groveEmitters = emitters.get(groveId);
        return groveEmitters == null ? 0 : groveEmitters.size();
    }

    private void remove(UUID groveId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> groveEmitters = emitters.get(groveId);
        if (groveEmitters != null) {
            groveEmitters.remove(emitter);
            if (groveEmitters.isEmpty()) {
                emitters.remove(groveId);
            }
        }
    }
}
