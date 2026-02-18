package dev.orchard.roots.entity;

import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.FruitState;
import dev.orchard.core.model.Seed;
import dev.orchard.harvest.SeedSerializer;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "fruits")
public class FruitEntity {

    private static final SeedSerializer seedSerializer = new SeedSerializer();

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID groveId;

    private UUID seedlingId;

    private String containerId;

    private String containerName;

    private String serviceName;

    @Column(nullable = false)
    private String state;

    @Column(columnDefinition = "TEXT")
    private String seedJson;

    private Instant buddedAt;

    private Instant ripenedAt;

    protected FruitEntity() {}

    public static FruitEntity fromModel(Fruit fruit) {
        FruitEntity entity = new FruitEntity();
        entity.id = fruit.id();
        entity.groveId = fruit.groveId();
        entity.seedlingId = fruit.seedlingId();
        entity.containerId = fruit.containerId();
        entity.containerName = fruit.containerName();
        entity.serviceName = fruit.serviceName();
        entity.state = fruit.state().name();
        entity.buddedAt = fruit.buddedAt();
        entity.ripenedAt = fruit.ripenedAt();

        if (fruit.seed() != null) {
            try {
                entity.seedJson = seedSerializer.serialize(fruit.seed());
            } catch (Exception e) {
                // Serialization failure should not prevent persistence
            }
        }

        return entity;
    }

    public Fruit toModel() {
        Seed seed = null;
        if (seedJson != null && !seedJson.isBlank()) {
            try {
                seed = seedSerializer.deserialize(seedJson);
            } catch (Exception e) {
                // Deserialization failure should not prevent model creation
            }
        }

        return new Fruit(
            id,
            groveId,
            seedlingId,
            containerId,
            containerName,
            serviceName,
            seed,
            FruitState.valueOf(state),
            List.of(),
            buddedAt,
            ripenedAt
        );
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getGroveId() { return groveId; }
    public UUID getSeedlingId() { return seedlingId; }
    public String getContainerId() { return containerId; }
    public String getContainerName() { return containerName; }
    public String getServiceName() { return serviceName; }
    public String getState() { return state; }
    public String getSeedJson() { return seedJson; }
    public Instant getBuddedAt() { return buddedAt; }
    public Instant getRipenedAt() { return ripenedAt; }

    // Setters for state updates
    public void setState(String state) { this.state = state; }
    public void setContainerId(String containerId) { this.containerId = containerId; }
    public void setRipenedAt(Instant ripenedAt) { this.ripenedAt = ripenedAt; }
}
