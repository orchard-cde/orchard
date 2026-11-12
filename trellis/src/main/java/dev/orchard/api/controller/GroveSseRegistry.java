package dev.orchard.api.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Holds the live SSE subscribers of {@code GET /api/groves/{groveId}/events}, keyed by grove,
 * so every component pushing onto that stream reaches the same connections.
 * <p>
 * Idle streams get an SSE comment every {@link #HEARTBEAT_INTERVAL}, which must stay well
 * under the idle timeouts of proxies in front of the API (nginx's default is 60 seconds).
 */
@Component
public class GroveSseRegistry {

    private static final Logger log = LoggerFactory.getLogger(GroveSseRegistry.class);
    private static final long SSE_TIMEOUT = 30 * 60 * 1000L;
    static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emitters;
    private final ScheduledExecutorService heartbeat;

    public GroveSseRegistry() {
        this(new ConcurrentHashMap<>());
    }

    GroveSseRegistry(ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emitters) {
        this(emitters, HEARTBEAT_INTERVAL);
    }

    GroveSseRegistry(ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emitters,
                     Duration heartbeatInterval) {
        this.emitters = emitters;
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        long millis = heartbeatInterval.toMillis();
        heartbeat.scheduleAtFixedRate(this::heartbeatTick, millis, millis, TimeUnit.MILLISECONDS);
    }

    private void heartbeatTick() {
        try {
            sendHeartbeats();
        } catch (RuntimeException e) {
            log.warn("SSE heartbeat tick failed", e);
        }
    }

    void sendHeartbeats() {
        emitters.forEach((groveId, groveEmitters) -> {
            for (SseEmitter emitter : groveEmitters) {
                send(groveId, emitter, SseEmitter.event().comment("heartbeat"), "heartbeat");
            }
        });
    }

    @PreDestroy
    void shutdown() {
        heartbeat.shutdownNow();
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
            send(groveId, emitter, SseEmitter.event().name(eventName).data(payload), eventName);
        }
    }

    private void send(UUID groveId, SseEmitter emitter, SseEmitter.SseEventBuilder event, String what) {
        try {
            emitter.send(event);
        } catch (IOException e) {
            log.debug("Subscriber for grove {} went away before {}", groveId, what);
            remove(groveId, emitter);
        } catch (RuntimeException e) {
            log.warn("Dropping SSE subscriber for grove {} after {} failed", groveId, what, e);
            remove(groveId, emitter);
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
