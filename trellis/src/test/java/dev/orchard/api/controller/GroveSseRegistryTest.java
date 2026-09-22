package dev.orchard.api.controller;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
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

    private static String rendered(SseEmitter.SseEventBuilder builder) {
        return builder.build().stream()
            .map(data -> String.valueOf(data.getData()))
            .collect(Collectors.joining());
    }
}
