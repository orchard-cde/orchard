package dev.orchard.core.model;

import java.time.Instant;
import java.util.UUID;

public record Bee(
    UUID id,
    UUID groveId,
    BeeType type,
    BeeState state,
    BeeSpec spec,
    String processId,
    Instant hatchedAt,
    Instant startedAt,
    Instant stoppedAt
) {
    public static Bee hatching(UUID groveId, BeeSpec spec) {
        return new Bee(
            UUID.randomUUID(), groveId, spec.type(), BeeState.HATCHING,
            spec, null, Instant.now(), null, null
        );
    }

    public Bee withState(BeeState newState) {
        return new Bee(id, groveId, type, newState, spec, processId,
            hatchedAt, startedAt, stoppedAt);
    }

    public Bee withProcessId(String processId) {
        return new Bee(id, groveId, type, state, spec, processId,
            hatchedAt, Instant.now(), stoppedAt);
    }

    public Bee withStoppedAt() {
        return new Bee(id, groveId, type, state, spec, processId,
            hatchedAt, startedAt, Instant.now());
    }

    public boolean isReady() {
        return state == BeeState.BUZZING && processId != null;
    }
}
