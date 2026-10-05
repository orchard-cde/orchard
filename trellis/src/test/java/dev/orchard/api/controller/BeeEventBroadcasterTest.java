package dev.orchard.api.controller;

import dev.orchard.api.event.BeeRemovedEvent;
import dev.orchard.api.event.BeeStateChangedEvent;
import dev.orchard.core.model.BeeState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BeeEventBroadcasterTest {

    private final GroveSseRegistry registry = mock(GroveSseRegistry.class);
    private final BeeEventBroadcaster broadcaster = new BeeEventBroadcaster(registry);

    private final UUID beeId = UUID.randomUUID();
    private final UUID groveId = UUID.randomUUID();
    private final Instant at = Instant.parse("2026-09-21T12:34:56Z");

    @Test
    void onBeeStateChanged_broadcastsBeeStateChangedToThatGrovesStream() {
        broadcaster.onBeeStateChanged(new BeeStateChangedEvent(
            beeId, groveId, BeeState.HIBERNATING, BeeState.BUZZING, at));

        assertThat(payloadFor("bee-state-changed")).isEqualTo(Map.of(
            "beeId", beeId.toString(),
            "groveId", groveId.toString(),
            "previousState", "HIBERNATING",
            "newState", "BUZZING",
            "changedAt", "2026-09-21T12:34:56Z"
        ));
    }

    @Test
    void onBeeRemoved_broadcastsBeeRemovedToThatGrovesStream() {
        broadcaster.onBeeRemoved(new BeeRemovedEvent(beeId, groveId, at));

        assertThat(payloadFor("bee-removed")).isEqualTo(Map.of(
            "beeId", beeId.toString(),
            "groveId", groveId.toString(),
            "removedAt", "2026-09-21T12:34:56Z"
        ));
    }

    private Object payloadFor(String eventName) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(registry).broadcast(eq(groveId), eq(eventName), payload.capture());
        return payload.getValue();
    }
}
