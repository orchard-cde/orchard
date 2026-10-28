package dev.orchard.roots.entity;

import dev.orchard.core.model.Grove;
import dev.orchard.core.model.GroveState;
import dev.orchard.core.model.Plot;
import dev.orchard.core.model.SeedSpec;
import dev.orchard.harvest.PlotSerializer;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "groves")
public class GroveEntity {

    private static final PlotSerializer plotSerializer = new PlotSerializer();

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeedSpec seedSpec = SeedSpec.AUTO;

    @Column(nullable = false)
    private Instant plantedAt;

    @Column(nullable = false)
    private Instant lastAccessedAt;

    @Column(columnDefinition = "TEXT")
    private String plotJson;

    protected GroveEntity() {}

    /**
     * Creates a GroveEntity from a Grove domain model.
     * Fruits are persisted separately via FruitEntity.
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
        entity.seedSpec = grove.seedSpec();
        entity.plantedAt = grove.plantedAt();
        entity.lastAccessedAt = grove.lastAccessedAt();

        entity.setPlot(grove.plot());

        return entity;
    }

    public UUID getId() { return id; }
    public UUID getCultivatorId() { return cultivatorId; }
    public String getName() { return name; }
    public String getRepositoryUrl() { return repositoryUrl; }
    public String getBranch() { return branch; }
    public String getCommitSha() { return commitSha; }
    public GroveState getState() { return state; }
    public SeedSpec getSeedSpec() { return seedSpec; }
    public Instant getPlantedAt() { return plantedAt; }
    public Instant getLastAccessedAt() { return lastAccessedAt; }

    public void setState(GroveState state) { this.state = state; }
    public void setLastAccessedAt(Instant lastAccessedAt) { this.lastAccessedAt = lastAccessedAt; }

    public Plot plot() {
        if (plotJson == null || plotJson.isBlank()) {
            throw new IllegalStateException("grove " + id + " has no plot_json; it predates the plot schema."
                + " trowel dev-server users: delete ~/.orchard/data/ once and restart");
        }
        return plotSerializer.deserialize(plotJson);
    }

    public void setPlot(Plot plot) {
        this.plotJson = serializePlot(plot);
    }

    public static String serializePlot(Plot plot) {
        return plot == null ? null : plotSerializer.serialize(plot);
    }
}
