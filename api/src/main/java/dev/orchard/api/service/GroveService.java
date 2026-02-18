package dev.orchard.api.service;

import dev.orchard.api.dto.CreateGroveRequest;
import dev.orchard.api.event.GroveStateChangedEvent;
import dev.orchard.core.model.*;
import dev.orchard.harvest.DevcontainerParser;
import dev.orchard.harvest.SeedSerializer;
import dev.orchard.nursery.FruitGrower;
import dev.orchard.nursery.ProviderRegistry;
import dev.orchard.nursery.SeedlingProvider;
import dev.orchard.nursery.SshExecutor;
import dev.orchard.roots.entity.FruitEntity;
import dev.orchard.roots.entity.GroveEntity;
import dev.orchard.roots.repository.FruitRepository;
import dev.orchard.roots.repository.GroveRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class GroveService {

    private static final Logger log = LoggerFactory.getLogger(GroveService.class);

    private final GroveRepository groveRepository;
    private final FruitRepository fruitRepository;
    private final ProviderRegistry providerRegistry;
    private final FruitGrower fruitGrower;
    private final CultivatorService cultivatorService;
    private final DevcontainerParser devcontainerParser;
    private final SeedSerializer seedSerializer;
    private final ApplicationEventPublisher eventPublisher;

    public GroveService(
            GroveRepository groveRepository,
            FruitRepository fruitRepository,
            ProviderRegistry providerRegistry,
            FruitGrower fruitGrower,
            CultivatorService cultivatorService,
            ApplicationEventPublisher eventPublisher) {
        this.groveRepository = groveRepository;
        this.fruitRepository = fruitRepository;
        this.providerRegistry = providerRegistry;
        this.fruitGrower = fruitGrower;
        this.cultivatorService = cultivatorService;
        this.devcontainerParser = new DevcontainerParser();
        this.seedSerializer = new SeedSerializer();
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Grove plantGrove(UUID cultivatorId, CreateGroveRequest request) {
        log.info("Planting grove for cultivator {} with repo {}", cultivatorId, request.repositoryUrl());

        // Ensure cultivator exists (auto-creates for local-dev)
        cultivatorService.ensureCultivator(cultivatorId);

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
        GroveEntity entity = GroveEntity.fromModel(grove);
        groveRepository.save(entity);

        // Start async provisioning after the transaction commits,
        // so the grove row exists before updateGroveState() runs
        final Grove groveToProvision = grove;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                CompletableFuture.runAsync(() -> provisionGrove(groveToProvision));
            }
        });

        return grove;
    }

    private void provisionGrove(Grove grove) {
        try {
            log.info("Starting provisioning for grove {}", grove.id());

            // Plant seedling (start VM)
            SeedlingProvider seedlingProvider = providerRegistry.getDefault();
            Seedling plantedSeedling = seedlingProvider.plant(grove.seedling()).join();
            grove = grove.withSeedling(plantedSeedling);
            grove = grove.withState(GroveState.GROWING);
            updateGroveState(grove);

            if (plantedSeedling.state() == SeedlingState.BLIGHTED) {
                grove = grove.withState(GroveState.BLIGHTED);
                updateGroveState(grove);
                return;
            }

            // Wait for cloud-init to finish (installs docker, git, etc.)
            waitForCloudInit(plantedSeedling);

            // Clone repository to VM
            String commitSha = cloneRepository(plantedSeedling, grove.repositoryUrl(), grove.branch());
            if (commitSha != null) {
                grove = grove.withCommit(commitSha);
                updateGroveState(grove);
            }

            // Discover devcontainer.json from the cloned repo on the VM
            Seed seed = discoverSeed(plantedSeedling);

            // Check if this is a Docker Compose-based workspace
            if (seed.dockerComposeFile() != null) {
                grove = provisionComposeFruits(grove, plantedSeedling, seed);
            } else {
                grove = provisionSingleFruit(grove, plantedSeedling, seed);
            }

            log.info("Grove {} is now {}", grove.id(), grove.state());

        } catch (Exception e) {
            log.error("Failed to provision grove {}", grove.id(), e);
            grove = grove.withState(GroveState.BLIGHTED);
            updateGroveState(grove);
        }
    }

    /**
     * Provisions a single fruit (the traditional single-container workflow).
     */
    private Grove provisionSingleFruit(Grove grove, Seedling seedling, Seed seed) {
        Fruit fruit = Fruit.bud(grove.id(), seedling.id(), seed);
        grove = grove.withFruit(fruit);
        saveFruits(grove.fruits());
        updateGroveState(grove);

        Fruit grownFruit = fruitGrower.grow(seedling, fruit).join();
        grove = grove.withFruit(grownFruit);
        saveFruits(grove.fruits());

        if (grownFruit.state() == FruitState.RIPE) {
            grove = grove.withState(GroveState.FLOURISHING);
        } else {
            grove = grove.withState(GroveState.BLIGHTED);
        }

        updateGroveState(grove);
        return grove;
    }

    /**
     * Provisions multiple fruits via Docker Compose.
     * Creates a Fruit for each service in the compose file, with the primary
     * service (specified by the seed's "service" field) listed first.
     */
    private Grove provisionComposeFruits(Grove grove, Seedling seedling, Seed seed) {
        String composeFile = seed.dockerComposeFile();
        String primaryService = seed.service();

        // Discover services from the compose file on the VM
        List<String> services = discoverComposeServices(seedling, composeFile);
        if (services.isEmpty()) {
            log.warn("No services found in compose file {}, falling back to single fruit", composeFile);
            return provisionSingleFruit(grove, seedling, seed);
        }

        // Create a fruit for each service, primary first
        List<Fruit> fruits = new ArrayList<>();
        if (primaryService != null && services.contains(primaryService)) {
            fruits.add(Fruit.bud(grove.id(), seedling.id(), seed, primaryService));
            for (String svc : services) {
                if (!svc.equals(primaryService)) {
                    Seed svcSeed = Seed.builder().name(svc).build();
                    fruits.add(Fruit.bud(grove.id(), seedling.id(), svcSeed, svc));
                }
            }
        } else {
            // No primary service specified; use all services in order
            for (String svc : services) {
                Seed svcSeed = svc.equals(services.getFirst()) ? seed :
                    Seed.builder().name(svc).build();
                fruits.add(Fruit.bud(grove.id(), seedling.id(), svcSeed, svc));
            }
        }

        grove = grove.withFruits(fruits);
        saveFruits(grove.fruits());
        updateGroveState(grove);

        // Grow all fruits via compose
        List<Fruit> grownFruits = fruitGrower.growCompose(seedling, fruits, composeFile).join();
        grove = grove.withFruits(grownFruits);
        saveFruits(grove.fruits());

        boolean allRipe = grownFruits.stream().allMatch(f -> f.state() == FruitState.RIPE);
        if (allRipe) {
            grove = grove.withState(GroveState.FLOURISHING);
        } else {
            grove = grove.withState(GroveState.BLIGHTED);
        }

        updateGroveState(grove);
        return grove;
    }

    /**
     * Discovers Docker Compose service names from a compose file on the VM.
     */
    private List<String> discoverComposeServices(Seedling seedling, String composeFile) {
        try {
            SshExecutor ssh = new SshExecutor(seedling);
            String composePath = composeFile.startsWith("/") ? composeFile : "/workspace/" + composeFile;
            String output = ssh.execute("docker compose -f " + composePath + " config --services");
            List<String> services = new ArrayList<>();
            for (String line : output.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    services.add(trimmed);
                }
            }
            return services;
        } catch (IOException | InterruptedException e) {
            log.error("Failed to discover compose services from {}: {}", composeFile, e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }

    private void waitForCloudInit(Seedling seedling) {
        log.info("Waiting for cloud-init to complete on seedling {}", seedling.id());
        SshExecutor ssh = new SshExecutor(seedling);
        int maxAttempts = 60; // 5 minutes max
        for (int i = 0; i < maxAttempts; i++) {
            try {
                String status = ssh.execute("cloud-init status 2>/dev/null || echo 'not available'").trim();
                if (status.contains("done") || status.contains("not available")) {
                    log.info("Cloud-init finished on seedling {} (status: {})", seedling.id(), status);
                    return;
                }
                log.debug("Cloud-init status on seedling {}: {}", seedling.id(), status);
            } catch (IOException | InterruptedException e) {
                log.debug("Cloud-init check failed on seedling {}: {}", seedling.id(), e.getMessage());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("Timeout waiting for cloud-init on seedling {}", seedling.id());
    }

    private String cloneRepository(Seedling seedling, String repoUrl, String branch) {
        log.info("Cloning {} branch {} to seedling {}", repoUrl, branch, seedling.id());
        try {
            SshExecutor ssh = new SshExecutor(seedling);
            ssh.execute("mkdir -p /workspace");
            ssh.execute(String.format("git clone --branch %s --depth 1 %s /workspace", branch, repoUrl));
            String commitSha = ssh.execute("git -C /workspace rev-parse HEAD").trim();
            log.info("Repository cloned, commit: {}", commitSha);
            return commitSha;
        } catch (IOException | InterruptedException e) {
            log.error("Failed to clone repository {} to seedling {}: {}", repoUrl, seedling.id(), e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private Seed discoverSeed(Seedling seedling) {
        SshExecutor ssh = new SshExecutor(seedling);

        // Try standard devcontainer locations
        for (String path : List.of(
                "/workspace/.devcontainer/devcontainer.json",
                "/workspace/.devcontainer.json")) {
            Optional<String> content = ssh.readFile(path);
            if (content.isPresent() && !content.get().isBlank()) {
                Optional<Seed> parsed = devcontainerParser.parseJson(content.get());
                if (parsed.isPresent()) {
                    log.info("Found and parsed devcontainer.json at {}", path);
                    return parsed.get();
                }
            }
        }

        // Fall back to default seed
        log.info("No devcontainer.json found, using default seed");
        return Seed.builder()
            .name("orchard-workspace")
            .image("mcr.microsoft.com/devcontainers/base:ubuntu")
            .forwardPorts(List.of("8080", "3000"))
            .build();
    }

    @Transactional
    public void updateGroveState(Grove grove) {
        try {
            // Look up previous state before persisting
            GroveState previousState = groveRepository.findById(grove.id())
                .map(GroveEntity::getState)
                .orElse(null);

            GroveEntity entity = GroveEntity.fromModel(grove);
            groveRepository.save(entity);

            // Publish event if state actually changed
            if (previousState != null && previousState != grove.state()) {
                eventPublisher.publishEvent(GroveStateChangedEvent.of(
                    grove.id(),
                    grove.cultivatorId(),
                    grove.name(),
                    previousState,
                    grove.state()
                ));
                log.info("Published state change event for grove {}: {} -> {}",
                    grove.id(), previousState, grove.state());
            }
        } catch (Exception e) {
            log.error("Failed to update grove state", e);
        }
    }

    /**
     * Saves all fruits for a grove to the database.
     */
    @Transactional
    public void saveFruits(List<Fruit> fruits) {
        if (fruits == null || fruits.isEmpty()) return;
        List<FruitEntity> entities = fruits.stream()
            .map(FruitEntity::fromModel)
            .toList();
        fruitRepository.saveAll(entities);
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

            Grove grove = entityToModel(entity);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    CompletableFuture.runAsync(() -> {
                        try {
                            // Check if VM is reachable before attempting container cleanup
                            boolean vmReachable = false;
                            if (grove.seedling() != null && grove.seedling().ipAddress() != null) {
                                try (var socket = new java.net.Socket()) {
                                    socket.connect(new java.net.InetSocketAddress(
                                        grove.seedling().ipAddress(), grove.seedling().sshPort()), 3000);
                                    vmReachable = true;
                                } catch (java.io.IOException e) {
                                    log.info("VM unreachable for grove {} — skipping container cleanup", groveId);
                                }
                            }

                            // Stop and remove all containers (only if VM is reachable)
                            if (vmReachable && grove.fruits() != null && !grove.fruits().isEmpty()) {
                                for (Fruit fruit : grove.fruits()) {
                                    if (fruit.containerId() != null) {
                                        log.info("Composting fruit {} for grove {}", fruit.id(), groveId);
                                        fruitGrower.compost(grove.seedling(), fruit).join();
                                    }
                                }
                            }
                            // Always clean up fruit entities from DB
                            fruitRepository.deleteAll(
                                fruitRepository.findByGroveId(groveId)
                            );
                            // Terminate VM
                            if (grove.seedling() != null) {
                                log.info("Uprooting seedling {} for grove {}", grove.seedling().id(), groveId);
                                providerRegistry.getDefault().uproot(grove.seedling()).join();
                            }
                        } catch (Exception e) {
                            log.error("Error during grove teardown for {}", groveId, e);
                        } finally {
                            entity.setState(GroveState.CLEARED);
                            groveRepository.save(entity);
                            log.info("Grove {} cleared", groveId);
                        }
                    });
                }
            });
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

        // Load fruits from the separate fruits table
        List<Fruit> fruits = fruitRepository.findByGroveId(entity.getId())
            .stream()
            .map(FruitEntity::toModel)
            .toList();

        return new Grove(
            entity.getId(),
            entity.getCultivatorId(),
            entity.getName(),
            entity.getRepositoryUrl(),
            entity.getBranch(),
            entity.getCommitSha(),
            entity.getState(),
            seedling,
            fruits,
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
