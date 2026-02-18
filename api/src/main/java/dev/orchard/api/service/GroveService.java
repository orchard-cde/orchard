package dev.orchard.api.service;

import dev.orchard.api.dto.CreateGroveRequest;
import dev.orchard.core.model.*;
import dev.orchard.harvest.DevcontainerParser;
import dev.orchard.harvest.SeedSerializer;
import dev.orchard.nursery.FruitGrower;
import dev.orchard.nursery.SeedlingProvider;
import dev.orchard.roots.entity.GroveEntity;
import dev.orchard.roots.repository.GroveRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class GroveService {

    private static final Logger log = LoggerFactory.getLogger(GroveService.class);

    private final GroveRepository groveRepository;
    private final SeedlingProvider seedlingProvider;
    private final FruitGrower fruitGrower;
    private final DevcontainerParser devcontainerParser;
    private final SeedSerializer seedSerializer;

    public GroveService(
            GroveRepository groveRepository,
            SeedlingProvider seedlingProvider,
            FruitGrower fruitGrower) {
        this.groveRepository = groveRepository;
        this.seedlingProvider = seedlingProvider;
        this.fruitGrower = fruitGrower;
        this.devcontainerParser = new DevcontainerParser();
        this.seedSerializer = new SeedSerializer();
    }

    @Transactional
    public Grove plantGrove(UUID cultivatorId, CreateGroveRequest request) {
        log.info("Planting grove for cultivator {} with repo {}", cultivatorId, request.repositoryUrl());

        // Create initial grove
        String name = request.name() != null ? request.name() :
            extractRepoName(request.repositoryUrl()) + "-" + request.branch();

        Grove grove = Grove.plant(cultivatorId, name, request.repositoryUrl(), request.branch());
        grove = grove.withState(GroveState.PLANTING);

        // Determine seedling spec
        Seedling.SeedlingSpec spec = switch (request.machineSize()) {
            case "medium" -> Seedling.SeedlingSpec.medium();
            case "large" -> Seedling.SeedlingSpec.large();
            default -> Seedling.SeedlingSpec.small();
        };

        // Create seedling
        Seedling seedling = Seedling.germinate(grove.id(), spec);
        grove = grove.withSeedling(seedling);

        // Save initial state
        GroveEntity entity = GroveEntity.fromModel(grove, null);
        groveRepository.save(entity);

        // Start async provisioning
        final Grove groveToProvision = grove;
        CompletableFuture.runAsync(() -> provisionGrove(groveToProvision));

        return grove;
    }

    private void provisionGrove(Grove grove) {
        try {
            log.info("Starting provisioning for grove {}", grove.id());

            // Plant seedling (start VM)
            Seedling plantedSeedling = seedlingProvider.plant(grove.seedling()).join();
            grove = grove.withSeedling(plantedSeedling);
            updateGroveState(grove);

            if (plantedSeedling.state() == SeedlingState.BLIGHTED) {
                grove = grove.withState(GroveState.BLIGHTED);
                updateGroveState(grove);
                return;
            }

            // Clone repository to VM
            cloneRepository(plantedSeedling, grove.repositoryUrl(), grove.branch());

            // Parse devcontainer.json
            // For now, use a default seed if none found
            Seed seed = Seed.builder()
                .name("orchard-workspace")
                .image("mcr.microsoft.com/devcontainers/base:ubuntu")
                .forwardPorts(List.of("8080", "3000"))
                .build();

            // Create and grow fruit
            Fruit fruit = Fruit.bud(grove.id(), plantedSeedling.id(), seed);
            grove = grove.withFruit(fruit);
            updateGroveState(grove);

            Fruit grownFruit = fruitGrower.grow(plantedSeedling, fruit).join();
            grove = grove.withFruit(grownFruit);

            if (grownFruit.state() == FruitState.RIPE) {
                grove = grove.withState(GroveState.FLOURISHING);
            } else {
                grove = grove.withState(GroveState.BLIGHTED);
            }

            updateGroveState(grove);
            log.info("Grove {} is now {}", grove.id(), grove.state());

        } catch (Exception e) {
            log.error("Failed to provision grove {}", grove.id(), e);
            grove = grove.withState(GroveState.BLIGHTED);
            updateGroveState(grove);
        }
    }

    private void cloneRepository(Seedling seedling, String repoUrl, String branch) {
        // TODO: Execute git clone via SSH to the seedling
        log.info("Would clone {} branch {} to seedling {}", repoUrl, branch, seedling.id());
    }

    @Transactional
    public void updateGroveState(Grove grove) {
        try {
            String seedJson = grove.fruit() != null && grove.fruit().seed() != null ?
                seedSerializer.serialize(grove.fruit().seed()) : null;
            GroveEntity entity = GroveEntity.fromModel(grove, seedJson);
            groveRepository.save(entity);
        } catch (Exception e) {
            log.error("Failed to update grove state", e);
        }
    }

    public Optional<Grove> getGrove(UUID groveId) {
        return groveRepository.findById(groveId)
            .map(this::entityToModel);
    }

    public List<Grove> getGrovesForCultivator(UUID cultivatorId) {
        return groveRepository.findByCultivatorId(cultivatorId)
            .stream()
            .map(this::entityToModel)
            .toList();
    }

    @Transactional
    public void clearGrove(UUID groveId) {
        groveRepository.findById(groveId).ifPresent(entity -> {
            log.info("Clearing grove {}", groveId);
            entity.setState(GroveState.CLEARING);
            groveRepository.save(entity);

            // TODO: Stop fruit and uproot seedling asynchronously
            entity.setState(GroveState.CLEARED);
            groveRepository.save(entity);
        });
    }

    private Grove entityToModel(GroveEntity entity) {
        Seedling seedling = null;
        if (entity.getSeedlingId() != null) {
            seedling = new Seedling(
                entity.getSeedlingId(),
                entity.getId(),
                null,
                entity.getSeedlingIpAddress(),
                entity.getSeedlingSshPort() != null ? entity.getSeedlingSshPort() : 22,
                entity.getSeedlingState(),
                new Seedling.SeedlingSpec(2, 4096, 20, "small"),
                entity.getPlantedAt(),
                null
            );
        }

        Fruit fruit = null;
        if (entity.getFruitId() != null) {
            Seed seed = null;
            if (entity.getSeedJson() != null) {
                try {
                    seed = seedSerializer.deserialize(entity.getSeedJson());
                } catch (Exception e) {
                    log.warn("Failed to deserialize seed", e);
                }
            }
            fruit = new Fruit(
                entity.getFruitId(),
                entity.getId(),
                entity.getSeedlingId(),
                null,
                null,
                seed,
                entity.getFruitState(),
                List.of(),
                entity.getPlantedAt(),
                null
            );
        }

        return new Grove(
            entity.getId(),
            entity.getCultivatorId(),
            entity.getName(),
            entity.getRepositoryUrl(),
            entity.getBranch(),
            entity.getCommitSha(),
            entity.getState(),
            seedling,
            fruit,
            entity.getPlantedAt(),
            entity.getLastAccessedAt()
        );
    }

    private String extractRepoName(String repoUrl) {
        String name = repoUrl;
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        return name;
    }
}
