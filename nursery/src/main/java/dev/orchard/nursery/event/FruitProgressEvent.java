package dev.orchard.nursery.event;

import java.util.UUID;

/**
 * Event published when a Fruit grow progresses through a recognisable phase boundary.
 *
 * <p>{@link dev.orchard.nursery.FruitGrower} emits one of these on phase transitions only —
 * never per CLI log line — so the broadcaster (Lane G) can fan them out to STOMP subscribers
 * without flooding the broker. See spec Locked decisions #15 (broadcaster placement) and #19
 * (phase taxonomy).
 *
 * <p><b>Module placement note:</b> the spec originally proposed
 * {@code api/.../event/FruitProgressEvent.java}, but FruitGrower lives in {@code nursery} which
 * sits below {@code api} in the dependency graph ({@code api -> nursery -> core}). Placing the
 * event in {@code api} would create a cycle. Lane G's broadcaster (in {@code trellis}) imports
 * this class directly — trellis already depends on nursery.
 *
 * @param fruitId     identifier of the Fruit whose grow this event describes
 * @param groveId     identifier of the Grove that owns the Fruit
 * @param phase       short phase tag (e.g. {@code BUILD_START}, {@code FEATURE_INSTALL_START},
 *                    {@code POST_CREATE_DONE}, {@code READY}, {@code ERROR})
 * @param detail      raw CLI JSON line (or other human-readable detail) for the UI's log tail
 * @param timestampMs wall-clock ms since epoch at the moment the phase was detected
 */
public record FruitProgressEvent(
    UUID fruitId,
    UUID groveId,
    String phase,
    String detail,
    long timestampMs
) {}
