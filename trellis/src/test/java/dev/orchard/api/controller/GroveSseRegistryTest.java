package dev.orchard.api.controller;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GroveSseRegistryTest {

    private final GroveSseRegistry registry = new GroveSseRegistry();
    private final UUID groveId = UUID.randomUUID();

    @Test
    void broadcast_sendsANamedEventToSubscribersOfThatGrove() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        registry.register(groveId, emitter);

        registry.broadcast(groveId, "bee-state-changed", Map.of("beeId", "b-1"));

        ArgumentCaptor<SseEmitter.SseEventBuilder> sent =
            ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(sent.capture());
        assertThat(rendered(sent.getValue()))
            .contains("event:bee-state-changed")
            .contains("b-1");
    }

    @Test
    void broadcast_ignoresSubscribersOfOtherGroves() throws IOException {
        SseEmitter otherGrove = mock(SseEmitter.class);
        registry.register(UUID.randomUUID(), otherGrove);

        registry.broadcast(groveId, "bee-state-changed", Map.of());

        verify(otherGrove, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void broadcast_withNoSubscribers_doesNothing() {
        assertThatNoException()
            .isThrownBy(() -> registry.broadcast(groveId, "bee-state-changed", Map.of()));
    }

    @Test
    void broadcast_detachesASubscriberThatHasDisconnected() throws IOException {
        SseEmitter gone = mock(SseEmitter.class);
        doThrow(new IOException("stream closed")).when(gone).send(any(SseEmitter.SseEventBuilder.class));
        registry.register(groveId, gone);

        registry.broadcast(groveId, "bee-state-changed", Map.of());
        registry.broadcast(groveId, "bee-state-changed", Map.of());

        verify(gone, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(registry.subscriberCount(groveId)).isZero();
    }

    @Test
    void subscribe_registersTheEmitterOnThatGrovesStream() {
        SseEmitter emitter = registry.subscribe(groveId);

        assertThat(emitter).isNotNull();
        assertThat(registry.subscriberCount(groveId)).isEqualTo(1);
    }

    @Test
    void subscriberCount_returnsToZeroWhenTheStreamCompletes() {
        SseEmitter emitter = mock(SseEmitter.class);
        ArgumentCaptor<Runnable> onCompletion = ArgumentCaptor.forClass(Runnable.class);
        registry.register(groveId, emitter);
        verify(emitter).onCompletion(onCompletion.capture());

        onCompletion.getValue().run();

        assertThat(registry.subscriberCount(groveId)).isZero();
    }

    @Test
    void broadcast_detachesASubscriberThatFailsUnexpectedly() throws IOException {
        SseEmitter broken = mock(SseEmitter.class);
        doThrow(new IllegalStateException("emitter already completed"))
            .when(broken).send(any(SseEmitter.SseEventBuilder.class));
        registry.register(groveId, broken);

        registry.broadcast(groveId, "bee-state-changed", Map.of());
        registry.broadcast(groveId, "bee-state-changed", Map.of());

        verify(broken, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(registry.subscriberCount(groveId)).isZero();
    }

    @Test
    void broadcast_keepsDeliveringAfterOneSubscriberFails() throws IOException {
        SseEmitter broken = mock(SseEmitter.class);
        doThrow(new IllegalStateException("emitter already completed"))
            .when(broken).send(any(SseEmitter.SseEventBuilder.class));
        SseEmitter healthy = mock(SseEmitter.class);
        registry.register(groveId, broken);
        registry.register(groveId, healthy);

        registry.broadcast(groveId, "bee-state-changed", Map.of());

        verify(healthy).send(any(SseEmitter.SseEventBuilder.class));
    }

    /** Records which mutating map operations the registry uses. */
    private static class RecordingMap extends ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> {
        final List<String> calls = new ArrayList<>();

        @Override
        public CopyOnWriteArrayList<SseEmitter> compute(UUID key,
                java.util.function.BiFunction<? super UUID, ? super CopyOnWriteArrayList<SseEmitter>,
                        ? extends CopyOnWriteArrayList<SseEmitter>> fn) {
            calls.add("compute");
            return super.compute(key, fn);
        }

        @Override
        public CopyOnWriteArrayList<SseEmitter> computeIfAbsent(UUID key,
                java.util.function.Function<? super UUID,
                        ? extends CopyOnWriteArrayList<SseEmitter>> fn) {
            calls.add("computeIfAbsent");
            return super.computeIfAbsent(key, fn);
        }

        @Override
        public CopyOnWriteArrayList<SseEmitter> computeIfPresent(UUID key,
                java.util.function.BiFunction<? super UUID, ? super CopyOnWriteArrayList<SseEmitter>,
                        ? extends CopyOnWriteArrayList<SseEmitter>> fn) {
            calls.add("computeIfPresent");
            return super.computeIfPresent(key, fn);
        }

        @Override
        public CopyOnWriteArrayList<SseEmitter> remove(Object key) {
            calls.add("remove");
            return super.remove(key);
        }
    }

    @Test
    void register_mutatesTheMapInOneAtomicOperation() {
        RecordingMap map = new RecordingMap();
        GroveSseRegistry seamed = new GroveSseRegistry(map);

        seamed.register(groveId, mock(SseEmitter.class));

        assertThat(map.calls)
            .as("registration must be a single atomic map operation, or it can interleave "
              + "with a removal evicting the key")
            .containsExactly("compute");
    }

    @Test
    void removingTheLastSubscriber_mutatesTheMapInOneAtomicOperation() {
        RecordingMap map = new RecordingMap();
        GroveSseRegistry seamed = new GroveSseRegistry(map);
        SseEmitter emitter = mock(SseEmitter.class);
        ArgumentCaptor<Runnable> onCompletion = ArgumentCaptor.forClass(Runnable.class);
        seamed.register(groveId, emitter);
        verify(emitter).onCompletion(onCompletion.capture());
        map.calls.clear();

        onCompletion.getValue().run();

        assertThat(map.calls)
            .as("removal must evaluate emptiness and evict the key under one lock")
            .containsExactly("computeIfPresent");
        assertThat(seamed.subscriberCount(groveId)).isZero();
    }

    @Test
    void register_isNeverStrandedWhenTheLastSubscriberLeavesConcurrently() throws Exception {
        int rounds = 500;
        int stranded = 0;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < rounds; round++) {
                UUID grove = UUID.randomUUID();

                SseEmitter leaving = mock(SseEmitter.class);
                ArgumentCaptor<Runnable> onCompletion = ArgumentCaptor.forClass(Runnable.class);
                registry.register(grove, leaving);
                verify(leaving).onCompletion(onCompletion.capture());
                Runnable disconnect = onCompletion.getValue();

                SseEmitter arriving = mock(SseEmitter.class);
                CountDownLatch go = new CountDownLatch(1);
                Future<?> leave = pool.submit(() -> {
                    go.await();
                    disconnect.run();
                    return null;
                });
                Future<?> arrive = pool.submit(() -> {
                    go.await();
                    registry.register(grove, arriving);
                    return null;
                });
                go.countDown();
                leave.get();
                arrive.get();

                if (registry.subscriberCount(grove) == 0) {
                    stranded++;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(stranded)
            .as("subscribers stranded in a list evicted from the map, out of %d rounds", rounds)
            .isZero();
    }

    private static String rendered(SseEmitter.SseEventBuilder builder) {
        return builder.build().stream()
            .map(data -> String.valueOf(data.getData()))
            .collect(Collectors.joining());
    }
}
