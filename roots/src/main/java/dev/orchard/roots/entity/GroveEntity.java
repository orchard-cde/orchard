package dev.orchard.roots.entity;

import dev.orchard.core.model.Grove;
import dev.orchard.core.model.GroveState;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "groves")
public class GroveEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID cultivatorId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String repositoryUrl;

    @Column(nullable = false)
    private String branch;

    private String commitSha;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GroveState state;

    @Column(nullable = false)
    private Instant plantedAt;

    @Column(nullable = false)
    private Instant lastAccessedAt;

    // Seedling fields (embedded for simplicity)
    private UUID seedlingId;
    private String seedlingProviderInstanceId;
    private String seedlingIpAddress;
    private Integer seedlingSshPort;
    @Enumerated(EnumType.STRING)
    private dev.orchard.core.model.SeedlingState seedlingState;
    private Integer seedlingCpuCores;
    private Integer seedlingMemoryMb;
    private Integer seedlingDiskGb;

    protected GroveEntity() {}

    /**
     * Creates a GroveEntity from a Grove domain model.
     * Note: Fruits are now stored separately in the fruits table via FruitEntity.
     */
    public static GroveEntity fromModel(Grove grove) {
        GroveEntity entity = new GroveEntity();
        entity.id = grove.id();
        entity.cultivatorId = grove.cultivatorId();
        entity.name = grove.name();
        entity.repositoryUrl = grove.repositoryUrl();
        entity.branch = grove.branch();
        entity.commitSha = grove.commitSha();
        entity.state = grove.state();
        entity.plantedAt = grove.plantedAt();
        entity.lastAccessedAt = grove.lastAccessedAt();

        if (grove.seedling() != null) {
            var s = grove.seedling();
            entity.seedlingId = s.id();
            entity.seedlingProviderInstanceId = s.providerInstanceId();
            entity.seedlingIpAddress = s.ipAddress();
            entity.seedlingSshPort = s.sshPort();
            entity.seedlingState = s.state();
            if (s.spec() != null) {
                entity.seedlingCpuCores = s.spec().cpuCores();
                entity.seedlingMemoryMb = s.spec().memoryMb();
                entity.seedlingDiskGb = s.spec().diskGb();
            }
        }

        return entity;
    }

    // Getters for query purposes
    public UUID getId() { return id; }
    public UUID getCultivatorId() { return cultivatorId; }
    public String getName() { return name; }
    public String getRepositoryUrl() { return repositoryUrl; }
    public String getBranch() { return branch; }
    public String getCommitSha() { return commitSha; }
    public GroveState getState() { return state; }
    public Instant getPlantedAt() { return plantedAt; }
    public Instant getLastAccessedAt() { return lastAccessedAt; }
    public UUID getSeedlingId() { return seedlingId; }
    public String getSeedlingIpAddress() { return seedlingIpAddress; }
    public Integer getSeedlingSshPort() { return seedlingSshPort; }
    public dev.orchard.core.model.SeedlingState getSeedlingState() { return seedlingState; }

    public void setState(GroveState state) { this.state = state; }
    public void setLastAccessedAt(Instant lastAccessedAt) { this.lastAccessedAt = lastAccessedAt; }
    public void setSeedlingState(dev.orchard.core.model.SeedlingState state) { this.seedlingState = state; }
    public void setSeedlingIpAddress(String ip) { this.seedlingIpAddress = ip; }
    public void setSeedlingProviderInstanceId(String id) { this.seedlingProviderInstanceId = id; }
}
