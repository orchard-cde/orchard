package dev.orchard.api.service;

import dev.orchard.api.dto.CreateGroveRequest;
import dev.orchard.api.event.GroveStateChangedEvent;
import dev.orchard.core.model.*;
import dev.orchard.harvest.DevcontainerParser;
import dev.orchard.harvest.DevfileParser;
import dev.orchard.harvest.SeedSerializer;
import dev.orchard.nursery.DevcontainerCliConfig;
import dev.orchard.nursery.GroveProvider;
import dev.orchard.nursery.ProviderRegistry;
import dev.orchard.nursery.SeedlingProvisioningException;
import dev.orchard.vine.CommandRunner;
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
import java.util.function.Function;

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
    private final CultivatorService cultivatorService;
    private final SshPublicKeyService sshPublicKeyService;
    private final DevcontainerParser devcontainerParser;
    private final DevfileParser devfileParser;
    private final SeedSerializer seedSerializer;
    private final ApplicationEventPublisher eventPublisher;

    public GroveService(
            GroveRepository groveRepository,
            FruitRepository fruitRepository,
            ProviderRegistry providerRegistry,
            DevcontainerCliConfig devcontainerCliConfig,
            CultivatorService cultivatorService,
            ApplicationEventPublisher eventPublisher,
            SshPublicKeyService sshPublicKeyService) {
        this.groveRepository = groveRepository;
        this.fruitRepository = fruitRepository;
        this.providerRegistry = providerRegistry;
        this.devcontainerCliConfig = devcontainerCliConfig;
        this.cultivatorService = cultivatorService;
        this.sshPublicKeyService = sshPublicKeyService;
        this.devcontainerParser = new DevcontainerParser();
        this.devfileParser = new DevfileParser();
        this.seedSerializer = new SeedSerializer();
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Grove plantGrove(UUID cultivatorId, CreateGroveRequest request) {
        log.info("Planting grove for cultivator {} with repo {}", cultivatorId, request.repositoryUrl());

        // Parse --spec up front so an invalid value fails before any rows are created.
        final SeedSpec seedSpec = SeedSpec.fromFlag(request.spec());

        cultivatorService.ensureCultivator(cultivatorId);

        String name = request.name() != null ? request.name() :
            extractRepoName(request.repositoryUrl()) + "-" + request.branch();

        Grove grove = Grove.plant(cultivatorId, name, request.repositoryUrl(), request.branch());
        grove = grove.withState(GroveState.PLANTING);
        grove = grove.withSeedSpec(seedSpec);

        GroveProvider provider = providerRegistry.getDefault();
        PlotSizes sizes = provider.sizes();
        SeedlingSpec seedlingSpec = switch (request.machineSize()) {
            case "medium" -> SeedlingSpec.of("medium", sizes.medium(), request.serialOutput());
            case "large" -> SeedlingSpec.of("large", sizes.large(), request.serialOutput());
            default -> SeedlingSpec.of("small", sizes.small(), request.serialOutput());
        };

        grove = grove.withPlot(provider.reserve(grove.id(), seedlingSpec));

        GroveEntity entity = GroveEntity.fromModel(grove);
        groveRepository.save(entity);

        // Start async provisioning after the transaction commits,
        // so the grove row exists before updateGroveState() runs
        final Grove groveToProvision = grove;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                CompletableFuture.runAsync(() -> provisionGrove(groveToProvision, seedSpec));
            }
        });

        return grove;
    }

    void provisionGrove(Grove grove, SeedSpec seedSpec) {
        try {
            log.info("Starting provisioning for grove {}", grove.id());

            // Bake the cultivator's registered SSH public keys into a VM plot so the
            // provider's cloud-init grants them access alongside the shared trellis key.
            Plot plotToPlant = grove.plot();
            if (plotToPlant instanceof VmPlot vm) {
                List<String> registeredKeys = sshPublicKeyService.listForCultivator(grove.cultivatorId())
                    .stream()
                    .map(SshPublicKey::publicKey)
                    .toList();
                plotToPlant = vm.withAuthorizedKeys(registeredKeys);
            }

            GroveProvider provider = providerRegistry.require(grove.plot().providerId());
            Plot plantedPlot = provider.plantSubstrate(plotToPlant).join();
            grove = grove.withPlot(plantedPlot);
            grove = grove.withState(GroveState.GROWING);
            updateGroveState(grove);

            if (plantedPlot.state() == PlotState.BLIGHTED) {
                grove = grove.withState(GroveState.BLIGHTED);
                updateGroveState(grove);
                return;
            }

            // Wait for cloud-init to finish (installs docker, git, node, devcontainer CLI, etc.)
            waitForCloudInit(provider, plantedPlot);

            // Verify devcontainer CLI was installed by cloud-init's runcmd block
            provider.verifyDevcontainerCli(plantedPlot, devcontainerCliConfig.version());

            String commitSha = cloneRepository(provider, plantedPlot, grove.repositoryUrl(), grove.branch());
            if (commitSha != null) {
                grove = grove.withCommit(commitSha);
                updateGroveState(grove);
            }

            // Discover seed from the cloned repo, honoring the requested --spec precedence.
            Seed seed = discoverSeed(provider, plantedPlot, seedSpec);

            // Ensure a devcontainer.json exists in the workspace when using the devcontainer
            // path — when the repo has none, we fall back to a default Seed but devcontainer
            // up still needs the file on disk. No-op for devfile-based seeds.
            ensureWorkspaceConfig(provider, plantedPlot, seed);

            grove = provisionPrimaryFruit(provider, grove, plantedPlot, seed);

            log.info("Grove {} is now {}", grove.id(), grove.state());

        } catch (Exception e) {
            log.error("Failed to provision grove {}", grove.id(), e);
            grove = grove.withState(GroveState.BLIGHTED);
            updateGroveState(grove);
        }
    }

    /**
     * Provisions the primary fruit for a grove. Dispatches on the concrete {@link Seed} type:
     *
     * <ul>
     *   <li>{@link DevcontainerSeed} — uses the devcontainer CLI path (or legacy docker path
     *       when the feature flag is off). Compose-mode is handled transparently by the CLI.</li>
     *   <li>{@link DevfileSeed} — runs the container directly from the image declared in the
     *       devfile {@code container} component. The devcontainer CLI is not involved.</li>
     * </ul>
     */
    private Grove provisionPrimaryFruit(GroveProvider provider, Grove grove, Plot plot, Seed seed) {
        String serviceName = switch (seed) {
            case DevcontainerSeed dc -> dc.dockerComposeFile() != null ? dc.service() : null;
            case DevfileSeed ignored -> null;
            default -> null;
        };

        Fruit fruit = Fruit.bud(grove.id(), plot.id(), seed, serviceName);
        grove = grove.withFruit(fruit);
        saveFruits(grove.fruits());
        updateGroveState(grove);

        Fruit grownFruit = provider.growFruit(plot, fruit).join();
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
     * authoritative check is {@link GroveProvider#verifyDevcontainerCli}, which directly probes
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

    private void waitForCloudInit(GroveProvider provider, Plot plot) {
        log.info("Waiting for cloud-init to complete on plot {}", plot.id());
        CommandRunner ssh = provider.vine(plot).commands();
        int maxAttempts = 60; // up to ~5 minutes
        for (int i = 0; i < maxAttempts; i++) {
            try {
                // `|| true` keeps the snapshot readable even when cloud-init reports a non-zero
                // (error/degraded) exit, which CommandRunner would otherwise raise — we classify the
                // state ourselves so a failed cloud-init is never mistaken for completion.
                String raw = ssh.execute("cloud-init status 2>/dev/null || true").trim();
                CloudInitStatus status = classifyCloudInitStatus(raw);
                if (status == CloudInitStatus.FAILED) {
                    throw new SeedlingProvisioningException(
                        "cloud-init failed on plot " + plot.id()
                            + " — provisioning cannot continue. Last 40 lines of "
                            + "/var/log/cloud-init-output.log:\n" + cloudInitLogTail(ssh));
                }
                if (status == CloudInitStatus.DONE) {
                    log.info("Cloud-init finished on plot {} ({})", plot.id(), raw);
                    return;
                }
                log.debug("Cloud-init still running on plot {}: {}", plot.id(), raw);
            } catch (IOException e) {
                log.debug("Cloud-init check failed on plot {}: {}", plot.id(), e.getMessage());
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
        log.warn("Timeout waiting for cloud-init on plot {} after {} attempts", plot.id(), maxAttempts);
    }

    /** Best-effort tail of the remote cloud-init log, for diagnosing a failed boot. */
    private String cloudInitLogTail(CommandRunner ssh) {
        try {
            return ssh.execute("sudo tail -n 40 /var/log/cloud-init-output.log 2>/dev/null || true").trim();
        } catch (IOException e) {
            return "(unable to read cloud-init log: " + e.getMessage() + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "(interrupted reading cloud-init log)";
        }
    }

    private String cloneRepository(GroveProvider provider, Plot plot, String repoUrl, String branch) {
        log.info("Cloning {} branch {} to plot {}", repoUrl, branch, plot.id());
        try {
            CommandRunner ssh = provider.vine(plot).commands();
            ssh.execute("mkdir -p /workspace");
            ssh.execute("git clone --branch %s --depth 1 %s /workspace".formatted(branch, repoUrl));
            String commitSha = ssh.execute("git -C /workspace rev-parse HEAD").trim();
            log.info("Repository cloned, commit: {}", commitSha);
            return commitSha;
        } catch (IOException | InterruptedException e) {
            log.error("Failed to clone repository {} to plot {}: {}", repoUrl, plot.id(), e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /** Standard on-VM locations for a devcontainer config, in precedence order. */
    private static final List<String> DEVCONTAINER_PATHS = List.of(
        "/workspace/.devcontainer/devcontainer.json",
        "/workspace/.devcontainer.json");

    /** Standard on-VM locations for a devfile, in precedence order. */
    private static final List<String> DEVFILE_PATHS = List.of(
        "/workspace/devfile.yaml",
        "/workspace/devfile.yml",
        "/workspace/.devfile.yaml",
        "/workspace/.devfile.yml");

    /** Shared defaults for a synthesized seed when a repo ships no workspace config. */
    private static final String DEFAULT_SEED_NAME = "orchard-workspace";
    private static final String DEFAULT_SEED_IMAGE = "mcr.microsoft.com/devcontainers/base:ubuntu";
    private static final List<String> DEFAULT_SEED_PORTS = List.of("8080", "3000");

    private Seed discoverSeed(GroveProvider provider, Plot plot, SeedSpec spec) {
        CommandRunner ssh = provider.vine(plot).commands();
        return resolveSeed(spec, ssh::readFile, devcontainerParser, devfileParser);
    }

    /**
     * Resolves the {@link Seed} for a cloned workspace — the precedence rule when a repo ships
     * both a {@code devcontainer.json} and a {@code devfile.yaml}:
     *
     * <ul>
     *   <li>{@link SeedSpec#AUTO} — devcontainer.json wins; devfile.yaml is the fallback; then a default seed.</li>
     *   <li>{@link SeedSpec#DEVCONTAINER} — devcontainer.json only; a default devcontainer seed when absent.</li>
     *   <li>{@link SeedSpec#DEVFILE} — devfile.yaml only; a default devfile seed when absent.</li>
     * </ul>
     *
     * <p>Reader-injected so the rules are unit-testable without a live VM.
     */
    static Seed resolveSeed(SeedSpec spec,
                            Function<String, Optional<String>> reader,
                            DevcontainerParser devcontainerParser,
                            DevfileParser devfileParser) {
        if (spec != SeedSpec.DEVFILE) {
            Optional<DevcontainerSeed> parsed = readFirst(DEVCONTAINER_PATHS, reader, devcontainerParser::parseJson);
            if (parsed.isPresent()) {
                return parsed.get();
            }
        }
        if (spec != SeedSpec.DEVCONTAINER) {
            Optional<DevfileSeed> parsed = readFirst(DEVFILE_PATHS, reader, devfileParser::parseYaml);
            if (parsed.isPresent()) {
                return parsed.get();
            }
        }

        // Nothing discovered — synthesize a default seed of the requested kind.
        if (spec == SeedSpec.DEVFILE) {
            log.info("No devfile found (spec=devfile); using default devfile seed");
            return defaultDevfileSeed();
        }
        log.info("No devcontainer.json or devfile found; using default devcontainer seed");
        return defaultDevcontainerSeed();
    }

    /** Reads the first present, non-blank, parseable file from {@code paths}. */
    private static <T extends Seed> Optional<T> readFirst(
            List<String> paths,
            Function<String, Optional<String>> reader,
            Function<String, Optional<T>> parser) {
        for (String path : paths) {
            Optional<String> content = reader.apply(path);
            if (content.isPresent() && !content.get().isBlank()) {
                Optional<T> parsed = parser.apply(content.get());
                if (parsed.isPresent()) {
                    log.info("Found and parsed workspace config at {}", path);
                    return parsed;
                }
                // Present but unparseable — warn so a broken config isn't indistinguishable
                // from an absent one when we fall through to a default seed.
                log.warn("Found workspace config at {} but could not parse it; skipping", path);
            }
        }
        return Optional.empty();
    }

    private static DevcontainerSeed defaultDevcontainerSeed() {
        return Seed.devcontainer()
            .name(DEFAULT_SEED_NAME)
            .image(DEFAULT_SEED_IMAGE)
            .forwardPorts(DEFAULT_SEED_PORTS)
            .build();
    }

    private static DevfileSeed defaultDevfileSeed() {
        return Seed.devfile()
            .name(DEFAULT_SEED_NAME)
            .componentName(DEFAULT_SEED_NAME)
            .image(DEFAULT_SEED_IMAGE)
            .forwardPorts(DEFAULT_SEED_PORTS)
            .build();
    }

    /**
     * Ensures the workspace on the plot has the configuration file it needs to start.
     *
     * <ul>
     *   <li>{@link DevcontainerSeed} — writes {@code /workspace/.devcontainer/devcontainer.json}
     *       if not already present, so that {@code devcontainer up} doesn't fail with
     *       "Config not found".</li>
     *   <li>{@link DevfileSeed} — no-op; the devfile is already on disk (we discovered it from
     *       the cloned repo), and the docker-direct grow path doesn't need a config file.</li>
     * </ul>
     */
    private void ensureWorkspaceConfig(GroveProvider provider, Plot plot, Seed seed) {
        switch (seed) {
            case DevcontainerSeed dc -> ensureDevcontainerConfig(provider, plot, dc);
            case DevfileSeed ignored -> log.debug(
                "Devfile-based seed — skipping devcontainer.json write on plot {}", plot.id());
            default -> log.warn("Unknown seed type {} — skipping workspace config write", seed.getClass().getSimpleName());
        }
    }

    /**
     * Ensures a devcontainer.json exists at /workspace/.devcontainer/devcontainer.json
     * on the plot. If the cloned repo already has one, this is a no-op (the file
     * test returns fast). Otherwise it serialises the given Seed to a valid
     * devcontainer.json so that {@code devcontainer up --workspace-folder /workspace}
     * does not fail with "Config not found".
     */
    private void ensureDevcontainerConfig(GroveProvider provider, Plot plot, DevcontainerSeed seed) {
        CommandRunner ssh = provider.vine(plot).commands();
        try {
            if (ssh.execute("test -f /workspace/.devcontainer/devcontainer.json && echo yes || echo no")
                    .trim().equals("yes")) {
                return;
            }
            log.info("Writing devcontainer.json to workspace on plot {} from seed", plot.id());

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
            log.warn("Failed to ensure devcontainer.json on plot {}", plot.id(), e);
        }
    }

    @Transactional
    public void updateGroveState(Grove grove) {
        try {
            GroveState previousState = groveRepository.findById(grove.id())
                .map(GroveEntity::getState)
                .orElse(null);

            GroveEntity entity = GroveEntity.fromModel(grove);
            groveRepository.save(entity);

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
            GroveState state = entity.getState();
            if (state == GroveState.CLEARING) {
                log.warn("Cannot clear grove {} in state {} — teardown already in flight", groveId, state);
                return;
            }
            if (state == GroveState.CLEARED) {
                log.warn("Cannot clear grove {} in state {}", groveId, state);
                return;
            }
            // Everything else stays clearable on purpose: ORPHANED is the retry path, and a grove
            // stuck in PREPARING/PLANTING/GROWING must still be removable. Returns void and the
            // controller always answers 204, so this guard's only effect is the warn above.
            log.info("Clearing grove {}", groveId);
            entity.setState(GroveState.CLEARING);
            groveRepository.save(entity);

            Grove grove = entityToModel(entity);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    CompletableFuture.runAsync(
                        () -> tearDownAndRecord(groveId, grove, entity, GroveState.CLEARED));
                }
            });
        });
    }

    /**
     * Tears down a grove's substrate, then records the outcome.
     *
     * <p>The substrate is released before the fruit rows are deleted, so a failure leaves the
     * records naming the leaked resource and the grove becomes {@link GroveState#ORPHANED}.
     *
     * <p>Package-private so tests can drive it directly; production enters via
     * {@code afterCommit} → {@code runAsync}, which no test can reach.
     */
    void tearDownAndRecord(UUID groveId, Grove grove, GroveEntity entity, GroveState successState) {
        // Phase 1 — release the substrate. A failure here may have left a resource behind, so
        // every record is retained and the grove is marked ORPHANED.
        try {
            compostFruitsIfReachable(groveId, grove);

            if (grove.plot() != null) {
                log.info("Uprooting plot {} for grove {}", grove.plot().id(), groveId);
                GroveProvider provider = providerRegistry.getDefault();
                provider.uproot(grove.plot()).join();
            }
        } catch (Exception e) {
            log.error("Substrate teardown failed for grove {} — marking ORPHANED and retaining "
                + "records; operator action required", groveId, e);
            entity.setState(GroveState.ORPHANED);
            groveRepository.save(entity);
            return;
        }

        // Phase 2 — the substrate is gone, so a failure here must not report ORPHANED: that
        // would send an operator hunting a resource that no longer exists. In-process only — if
        // the save() below is what fails, the row keeps CLEARING and the reconciler marks it
        // ORPHANED at the next startup instead.
        try {
            // On the stop path this detached entity already holds successState, so rewriting it
            // would clobber a startGrove that ran in between. Guards the state column only — the
            // fruit rows are protected separately, below.
            if (entity.getState() != successState) {
                entity.setState(successState);
                groveRepository.save(entity);
            }
            // Only the generation captured when teardown began: re-querying at completion would
            // delete rows a subsequent startGrove created, since this runs asynchronously.
            List<UUID> capturedFruitIds = grove.fruits() == null ? List.of()
                : grove.fruits().stream().map(Fruit::id).toList();
            fruitRepository.deleteAllById(capturedFruitIds);
            log.info("Grove {} reached {}", groveId, successState);
        } catch (Exception e) {
            log.error("Grove {} substrate was released but bookkeeping failed; state or fruit rows "
                + "may be stale. The substrate is NOT leaked.", groveId, e);
        }
    }

    /**
     * Best-effort container cleanup, gated on a raw socket probe.
     *
     * <p>The probe is a known defect carried forward deliberately: a substrate-aware reachability
     * check obtained through the provider would be correct, but needs provider-owned inspection
     * (#228). Do not opportunistically replace it here. A probe miss no
     * longer masks an {@code uproot} failure.
     */
    private void compostFruitsIfReachable(UUID groveId, Grove grove) {
        boolean vmReachable = false;
        if (grove.plot() instanceof VmPlot vm && vm.host() != null) {
            try (var socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(vm.host(), vm.sshPort()), 3000);
                vmReachable = true;
            } catch (java.io.IOException e) {
                log.info("VM unreachable for grove {} — skipping container cleanup", groveId);
            }
        }
        if (vmReachable && grove.fruits() != null && !grove.fruits().isEmpty()) {
            GroveProvider provider = providerRegistry.getDefault();
            for (Fruit fruit : grove.fruits()) {
                if (fruit.containerId() != null) {
                    log.info("Composting fruit {} for grove {}", fruit.id(), groveId);
                    provider.compostFruit(grove.plot(), fruit).join();
                }
            }
        }
    }

    /**
     * Stops a running grove by tearing down its fruit and plot, transitioning it to
     * {@link GroveState#DORMANT}. Only groves in {@link GroveState#GROWING} or
     * {@link GroveState#FLOURISHING} can be stopped.
     */
    @Transactional
    public Optional<Grove> stopGrove(UUID groveId) {
        return groveRepository.findById(groveId).map(entity -> {
            GroveState state = entity.getState();
            if (state != GroveState.GROWING && state != GroveState.FLOURISHING) {
                log.warn("Cannot stop grove {} in state {}", groveId, state);
                return entityToModel(entity);
            }

            log.info("Stopping grove {}", groveId);
            entity.setState(GroveState.DORMANT);
            groveRepository.save(entity);

            Grove grove = entityToModel(entity);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    CompletableFuture.runAsync(
                        () -> tearDownAndRecord(groveId, grove, entity, GroveState.DORMANT));
                }
            });

            return grove;
        });
    }

    /**
     * Restarts a dormant grove by re-provisioning its plot and fruit, transitioning it
     * back to {@link GroveState#GROWING} / {@link GroveState#FLOURISHING}.
     * Only groves in {@link GroveState#DORMANT} can be started.
     */
    @Transactional
    public Optional<Grove> startGrove(UUID groveId) {
        return groveRepository.findById(groveId).map(entity -> {
            GroveState state = entity.getState();
            if (state != GroveState.DORMANT) {
                log.warn("Cannot start grove {} in state {}", groveId, state);
                return entityToModel(entity);
            }

            log.info("Starting grove {}", groveId);
            Grove grove = entityToModel(entity);
            grove = grove.withPlot(providerRegistry.require(grove.plot().providerId())
                .reserve(grove.id(), SeedlingSpec.from(grove.plot())));
            grove = grove.withState(GroveState.PLANTING);
            updateGroveState(grove);

            final Grove groveToProvision = grove;
            final SeedSpec seedSpec = grove.seedSpec() != null ? grove.seedSpec() : SeedSpec.AUTO;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    CompletableFuture.runAsync(() -> provisionGrove(groveToProvision, seedSpec));
                }
            });

            return grove;
        });
    }

    private Grove entityToModel(GroveEntity entity) {
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
            entity.getSeedSpec() != null ? entity.getSeedSpec() : SeedSpec.AUTO,
            entity.plot(),
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
