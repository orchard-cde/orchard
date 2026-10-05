package dev.orchard.api.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
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

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emitters;

    public GroveSseRegistry() {
        this(new ConcurrentHashMap<>());
    }

    GroveSseRegistry(ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emitters) {
        this.emitters = emitters;
    }

    public SseEmitter subscribe(UUID groveId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        register(groveId, emitter);
        log.debug("New SSE subscriber for grove {}", groveId);
        return emitter;
    }

    void register(UUID groveId, SseEmitter emitter) {
        // Add inside compute() so it cannot interleave with remove() evicting a grove whose
        // last subscriber just left; adding to a list already dropped from the map would
        // leave the new subscriber silently receiving nothing.
        emitters.compute(groveId, (key, groveEmitters) -> {
            CopyOnWriteArrayList<SseEmitter> target =
                groveEmitters != null ? groveEmitters : new CopyOnWriteArrayList<>();
            target.add(emitter);
            return target;
        });

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
            } catch (RuntimeException e) {
                log.warn("Dropping SSE subscriber for grove {} after event {} failed", groveId, eventName, e);
                remove(groveId, emitter);
            }
        }
    }

    int subscriberCount(UUID groveId) {
        CopyOnWriteArrayList<SseEmitter> groveEmitters = emitters.get(groveId);
        return groveEmitters == null ? 0 : groveEmitters.size();
    }

    private void remove(UUID groveId, SseEmitter emitter) {
        emitters.computeIfPresent(groveId, (key, groveEmitters) -> {
            groveEmitters.remove(emitter);
            return groveEmitters.isEmpty() ? null : groveEmitters;
        });
    }
}
