package dev.orchard.api.dto;

import dev.orchard.core.model.Bee;
import dev.orchard.core.model.BeeState;
import dev.orchard.core.model.BeeType;

import java.time.Instant;
import java.util.UUID;

public record BeeResponse(
    UUID id,
    UUID groveId,
    BeeType type,
    BeeState state,
    String processId,
    Instant hatchedAt,
    Instant startedAt,
    Instant stoppedAt
) {
    public static BeeResponse fromModel(Bee bee) {
        return new BeeResponse(
            bee.id(), bee.groveId(), bee.type(), bee.state(),
            bee.processId(), bee.hatchedAt(), bee.startedAt(), bee.stoppedAt()
        );
    }
}
