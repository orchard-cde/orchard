package dev.orchard.server.websocket;

import dev.orchard.api.event.GroveStateChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Listens for {@link GroveStateChangedEvent} and broadcasts state changes
 * to WebSocket subscribers via STOMP.
 * <p>
 * Messages are sent to two topics:
 * <ul>
 *   <li>/topic/grove.{groveId} - for clients watching a specific grove</li>
 *   <li>/topic/cultivator.{cultivatorId}.groves - for clients watching all groves
 *       belonging to a cultivator (e.g., the dashboard)</li>
 * </ul>
 */
@Component
public class GroveEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(GroveEventBroadcaster.class);

    private final SimpMessagingTemplate messagingTemplate;

    public GroveEventBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @EventListener
    public void onGroveStateChanged(GroveStateChangedEvent event) {
        GroveStateMessage message = GroveStateMessage.from(event);

        String groveTopic = "/topic/grove." + event.groveId();
        String cultivatorTopic = "/topic/cultivator." + event.cultivatorId() + ".groves";

        log.debug("Broadcasting grove state change to {} and {}: {} -> {}",
            groveTopic, cultivatorTopic, event.previousState(), event.newState());

        messagingTemplate.convertAndSend(groveTopic, message);
        messagingTemplate.convertAndSend(cultivatorTopic, message);
    }
}
