package dev.orchard.roots.entity;

import dev.orchard.core.model.Bee;
import dev.orchard.core.model.BeeSpec;
import dev.orchard.core.model.BeeState;
import dev.orchard.core.model.BeeType;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "bees")
public class BeeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID groveId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BeeType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BeeState state;

    @Column(nullable = false)
    private String specType;

    private String specVersion;

    private String processId;

    @Column(nullable = false)
    private Instant hatchedAt;

    private Instant startedAt;

    private Instant stoppedAt;

    protected BeeEntity() {}

    public static BeeEntity fromModel(Bee bee) {
        BeeEntity entity = new BeeEntity();
        entity.id = bee.id();
        entity.groveId = bee.groveId();
        entity.type = bee.type();
        entity.state = bee.state();
        entity.specType = bee.spec().type().name();
        entity.specVersion = bee.spec().version();
        entity.processId = bee.processId();
        entity.hatchedAt = bee.hatchedAt();
        entity.startedAt = bee.startedAt();
        entity.stoppedAt = bee.stoppedAt();
        return entity;
    }

    public Bee toModel() {
        BeeSpec spec = new BeeSpec(
            BeeType.valueOf(specType),
            specVersion,
            Map.of()
        );
        return new Bee(id, groveId, type, state, spec, processId, hatchedAt, startedAt, stoppedAt);
    }

    public UUID getId() { return id; }
    public UUID getGroveId() { return groveId; }
    public BeeType getType() { return type; }
    public BeeState getState() { return state; }
    public String getProcessId() { return processId; }
    public Instant getHatchedAt() { return hatchedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getStoppedAt() { return stoppedAt; }

    public void setState(BeeState state) { this.state = state; }
    public void setProcessId(String processId) { this.processId = processId; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public void setStoppedAt(Instant stoppedAt) { this.stoppedAt = stoppedAt; }
}
