package dev.orchard.trellis.websocket;

import dev.orchard.nursery.event.FruitProgressEvent;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FruitProgressBroadcasterTest {

    @Test
    void publishesToBothTopics() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        FruitProgressBroadcaster broadcaster = new FruitProgressBroadcaster(template);

        UUID fruitId = UUID.randomUUID();
        UUID groveId = UUID.randomUUID();
        FruitProgressEvent event = new FruitProgressEvent(
            fruitId, groveId, "BUILD_START", null, System.currentTimeMillis());

        broadcaster.onFruitProgress(event);

        verify(template).convertAndSend(eq("/topic/fruit." + fruitId + ".progress"), eq((Object) event));
        verify(template).convertAndSend(eq("/topic/grove." + groveId + ".fruits"), eq((Object) event));
    }

    @Test
    void swallowsExceptions() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        doThrow(new RuntimeException("STOMP broker down"))
            .when(template).convertAndSend(anyString(), any(Object.class));

        FruitProgressBroadcaster broadcaster = new FruitProgressBroadcaster(template);
        FruitProgressEvent event = new FruitProgressEvent(
            UUID.randomUUID(), UUID.randomUUID(), "ERROR", null, 0);

        assertThatCode(() -> broadcaster.onFruitProgress(event)).doesNotThrowAnyException();
    }
}
