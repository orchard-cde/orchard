package dev.orchard.trellis.websocket;

import dev.orchard.nursery.event.FruitProgressEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Bridges {@link FruitProgressEvent} (published by {@code FruitGrower}) to STOMP subscribers.
 * <p>
 * Messages are sent to two topics:
 * <ul>
 *   <li>{@code /topic/fruit.{fruitId}.progress} — clients watching a single fruit's grow</li>
 *   <li>{@code /topic/grove.{groveId}.fruits} — clients watching a grove's fruits (dashboard)</li>
 * </ul>
 * Best-effort: {@code convertAndSend} failures are logged and swallowed — progress events are
 * observability, not correctness (see spec Locked decision #17). A broker outage must never
 * cause a Fruit grow to fail.
 */
@Component
@RegisterReflectionForBinding(FruitProgressEvent.class)
public class FruitProgressBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(FruitProgressBroadcaster.class);

    private final SimpMessagingTemplate messagingTemplate;

    public FruitProgressBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @EventListener
    public void onFruitProgress(FruitProgressEvent event) {
        String fruitTopic = "/topic/fruit." + event.fruitId() + ".progress";
        String groveTopic = "/topic/grove." + event.groveId() + ".fruits";

        log.debug("Broadcasting fruit progress to {} and {}: phase={}",
            fruitTopic, groveTopic, event.phase());

        try {
            messagingTemplate.convertAndSend(fruitTopic, event);
            messagingTemplate.convertAndSend(groveTopic, event);
        } catch (Exception e) {
            log.warn("FruitProgressEvent broadcast failed for fruit {} (phase={}); swallowing",
                event.fruitId(), event.phase(), e);
        }
    }
}
