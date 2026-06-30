package dev.orchard.api.service;

import dev.orchard.api.dto.CreateGroveRequest;
import dev.orchard.api.event.GroveStateChangedEvent;
import dev.orchard.core.model.*;
import dev.orchard.harvest.DevcontainerParser;
import dev.orchard.harvest.SeedSerializer;
import dev.orchard.nursery.*;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class GroveService {

    private static final Logger log = LoggerFactory.getLogger(GroveService.class);
    private static final ObjectMapper devcontainerMapper = JsonMapper.builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .build();

    private final GroveRepository groveRepository;
    private final FruitRepository fruitRepository;
    private final ProviderRegistry providerRegistry;
    private final DevcontainerCliConfig devcontainerCliConfig;
    private final FruitGrower fruitGrower;
    private final CultivatorService cultivatorService;
    private final DevcontainerParser devcontainerParser;
    private final SeedSerializer seedSerializer;
    private final ApplicationEventPublisher eventPublisher;

    public GroveService(
            GroveRepository groveRepository,
            FruitRepository fruitRepository,
            ProviderRegistry providerRegistry,
            DevcontainerCliConfig devcontainerCliConfig,
            FruitGrower fruitGrower,
            CultivatorService cultivatorService,
            ApplicationEventPublisher eventPublisher) {
        this.groveRepository = groveRepository;
        this.fruitRepository = fruitRepository;
        this.providerRegistry = providerRegistry;
        this.devcontainerCliConfig = devcontainerCliConfig;
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
            case "medium" -> new Seedling.SeedlingSpec(4, 8192, 40, "medium", request.serialOutput());
            case "large" -> new Seedling.SeedlingSpec(8, 16384, 80, "large", request.serialOutput());
            default -> new Seedling.SeedlingSpec(2, 4096, 20, "small", request.serialOutput());
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

            // Wait for cloud-init to finish (installs docker, git, node, devcontainer CLI, etc.)
            waitForCloudInit(plantedSeedling);

            // Verify devcontainer CLI was installed by cloud-init's runcmd block
            seedlingProvider.verifyDevcontainerCli(plantedSeedling, devcontainerCliConfig.version());

            // Clone repository to VM
            String commitSha = cloneRepository(plantedSeedling, grove.repositoryUrl(), grove.branch());
            if (commitSha != null) {
                grove = grove.withCommit(commitSha);
                updateGroveState(grove);
            }

            // Discover devcontainer.json from the cloned repo on the VM
            DevcontainerSeed seed = discoverSeed(plantedSeedling);

            // Ensure a devcontainer.json exists in the workspace — when the repo has none,
            // we fall back to a default Seed but devcontainer up still needs the file on disk.
            ensureDevcontainerConfig(plantedSeedling, seed);

            // Single grow path for both single-container and compose-mode devcontainers.
            // For compose-mode (seed.dockerComposeFile() != null), the devcontainer CLI brings
            // up the entire compose stack as a side effect of `devcontainer up`. Only the
            // primary service (seed.service()) is tracked as a Fruit; sibling containers run
            // but are not represented as separate Fruit records yet — see follow-up below.
            //
            // Spec Locked decision #11: sibling-service enumeration via `docker compose ps`
            // is deferred so this PR can stop routing compose through the deprecated
            // FruitGrower#growCompose path. File-tracking siblings is a future PR.
            grove = provisionPrimaryFruit(grove, plantedSeedling, seed);

            log.info("Grove {} is now {}", grove.id(), grove.state());

        } catch (Exception e) {
            log.error("Failed to provision grove {}", grove.id(), e);
            grove = grove.withState(GroveState.BLIGHTED);
            updateGroveState(grove);
        }
    }

    /**
     * Provisions the primary fruit for a grove. Works for both single-container devcontainers
     * and compose-mode devcontainers — in the latter case the devcontainer CLI brings up the
     * entire compose stack as a side effect of {@code devcontainer up} on the primary service.
     *
     * <p>For compose-mode, only the primary service is tracked as a {@link Fruit}; sibling
     * service containers run on the seedling but are not represented as separate Fruit rows.
     * Sibling enumeration via {@code docker compose ps} is a follow-up PR (spec #11).
     */
    private Grove provisionPrimaryFruit(Grove grove, Seedling seedling, DevcontainerSeed seed) {
        // For compose-mode, attach the primary service name so consumers can correlate the
        // Fruit with the compose service. For single-container, serviceName stays null.
        // TODO: serviceName should evolve to fruitName — root compose service name in
        // compose mode, repo-name-with-uniqueness applied otherwise. Tracked for follow-up
        // per reviewer feedback on PR #110.
        String serviceName = seed.dockerComposeFile() != null ? seed.service() : null;
        Fruit fruit = Fruit.bud(grove.id(), seedling.id(), seed, serviceName);
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

    /** Terminal classification of a {@code cloud-init status} snapshot. */
    enum CloudInitStatus { IN_PROGRESS, DONE, FAILED }

    /**
     * Classifies a raw {@code cloud-init status} line (e.g. {@code "status: running"}).
     *
     * <p>Only an explicit terminal-and-healthy state is reported as {@link CloudInitStatus#DONE};
     * an errored cloud-init is reported as {@link CloudInitStatus#FAILED} rather than mistaken for
     * completion, and an empty/unreadable snapshot stays {@link CloudInitStatus#IN_PROGRESS}. The
     * previous {@code contains("done") || contains("not available")} check masked failures and an
     * unanswerable status, surfacing them downstream as a confusing
     * {@code devcontainer: command not found} (issue #113).
     *
     * <p>{@code "degraded done"} (cloud-init finished with recoverable errors) counts as DONE — the
     * authoritative check is {@link SeedlingProvider#verifyDevcontainerCli}, which directly probes
     * the CLI rather than trusting cloud-init's self-report.
     */
    static CloudInitStatus classifyCloudInitStatus(String raw) {
        String state = raw == null ? "" : raw.trim();
        int colon = state.indexOf(':');
        if (colon >= 0 && state.regionMatches(true, 0, "status", 0, "status".length())) {
            state = state.substring(colon + 1).trim();
        }
        state = state.toLowerCase(java.util.Locale.ROOT);

        if ("error".equals(state)) {
            return CloudInitStatus.FAILED;
        }
        if (state.isEmpty() || "running".equals(state) || "not run".equals(state)) {
            return CloudInitStatus.IN_PROGRESS;
        }
        // "done", "degraded done", "disabled" — cloud-init is no longer working.
        return CloudInitStatus.DONE;
    }

    private void waitForCloudInit(Seedling seedling) {
        log.info("Waiting for cloud-init to complete on seedling {}", seedling.id());
        SshExecutor ssh = new SshExecutor(seedling);
        int maxAttempts = 60; // up to ~5 minutes
        for (int i = 0; i < maxAttempts; i++) {
            try {
                // `|| true` keeps the snapshot readable even when cloud-init reports a non-zero
                // (error/degraded) exit, which SshExecutor would otherwise raise — we classify the
                // state ourselves so a failed cloud-init is never mistaken for completion.
                String raw = ssh.execute("cloud-init status 2>/dev/null || true").trim();
                CloudInitStatus status = classifyCloudInitStatus(raw);
                if (status == CloudInitStatus.FAILED) {
                    throw new SeedlingProvisioningException(
                        "cloud-init failed on seedling " + seedling.id()
                            + " — provisioning cannot continue. Last 40 lines of "
                            + "/var/log/cloud-init-output.log:\n" + cloudInitLogTail(ssh));
                }
                if (status == CloudInitStatus.DONE) {
                    log.info("Cloud-init finished on seedling {} ({})", seedling.id(), raw);
                    return;
                }
                log.debug("Cloud-init still running on seedling {}: {}", seedling.id(), raw);
            } catch (IOException e) {
                log.debug("Cloud-init check failed on seedling {}: {}", seedling.id(), e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("Timeout waiting for cloud-init on seedling {} after {} attempts", seedling.id(), maxAttempts);
    }

    /** Best-effort tail of the remote cloud-init log, for diagnosing a failed boot. */
    private String cloudInitLogTail(SshExecutor ssh) {
        try {
            return ssh.execute("sudo tail -n 40 /var/log/cloud-init-output.log 2>/dev/null || true").trim();
        } catch (IOException e) {
            return "(unable to read cloud-init log: " + e.getMessage() + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "(interrupted reading cloud-init log)";
        }
    }

    private String cloneRepository(Seedling seedling, String repoUrl, String branch) {
        log.info("Cloning {} branch {} to seedling {}", repoUrl, branch, seedling.id());
        try {
            SshExecutor ssh = new SshExecutor(seedling);
            ssh.execute("mkdir -p /workspace");
            ssh.execute("git clone --branch %s --depth 1 %s /workspace".formatted(branch, repoUrl));
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

    private DevcontainerSeed discoverSeed(Seedling seedling) {
        SshExecutor ssh = new SshExecutor(seedling);

        // Try standard devcontainer locations
        for (String path : List.of(
                "/workspace/.devcontainer/devcontainer.json",
                "/workspace/.devcontainer.json")) {
            Optional<String> content = ssh.readFile(path);
            if (content.isPresent() && !content.get().isBlank()) {
                Optional<DevcontainerSeed> parsed = devcontainerParser.parseJson(content.get());
                if (parsed.isPresent()) {
                    log.info("Found and parsed devcontainer.json at {}", path);
                    return parsed.get();
                }
            }
        }

        // Fall back to default seed
        log.info("No devcontainer.json found, using default seed");
        return DevcontainerSeed.builder()
            .name("orchard-workspace")
            .image("mcr.microsoft.com/devcontainers/base:ubuntu")
            .forwardPorts(List.of("8080", "3000"))
            .build();
    }

    /**
     * Ensures a devcontainer.json exists at /workspace/.devcontainer/devcontainer.json
     * on the seedling. If the cloned repo already has one, this is a no-op (the file
     * test returns fast). Otherwise it serialises the given Seed to a valid
     * devcontainer.json so that {@code devcontainer up --workspace-folder /workspace}
     * does not fail with "Config not found".
     */
    private void ensureDevcontainerConfig(Seedling seedling, DevcontainerSeed seed) {
        SshExecutor ssh = new SshExecutor(seedling);
        try {
            if (ssh.execute("test -f /workspace/.devcontainer/devcontainer.json && echo yes || echo no")
                    .trim().equals("yes")) {
                return;
            }
            log.info("Writing devcontainer.json to workspace on seedling {} from seed", seedling.id());

            ObjectNode root = devcontainerMapper.createObjectNode();
            if (seed.image() != null) {
                root.put("image", seed.image());
            }
            if (!seed.forwardPorts().isEmpty()) {
                ArrayNode ports = root.putArray("forwardPorts");
                for (String port : seed.forwardPorts()) {
                    try { ports.add(Integer.parseInt(port)); }
                    catch (NumberFormatException e) { ports.add(port); }
                }
            }
            if (!seed.features().isEmpty()) {
                root.set("features", devcontainerMapper.valueToTree(seed.features()));
            }
            if (!seed.containerEnv().isEmpty()) {
                root.set("containerEnv", devcontainerMapper.valueToTree(seed.containerEnv()));
            }

            String json = devcontainerMapper.writeValueAsString(root);
            String b64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            ssh.execute("mkdir -p /workspace/.devcontainer && echo '" + b64 + "' | base64 -d > /workspace/.devcontainer/devcontainer.json");
        } catch (IOException | InterruptedException e) {
            log.warn("Failed to ensure devcontainer.json on seedling {}", seedling.id(), e);
        }
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
        if (fruits == null || fruits.isEmpty()) {
            return;
        }
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
        return getGrovesForCultivator(cultivatorId, false);
    }

    public List<Grove> getGrovesForCultivator(UUID cultivatorId, boolean all) {
        var entities = all
            ? groveRepository.findByCultivatorId(cultivatorId)
            : groveRepository.findByCultivatorIdAndStateNotIn(cultivatorId, List.of(GroveState.CLEARED));
        return entities.stream()
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
                new Seedling.SeedlingSpec(2, 4096, 20, "small", null),
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
