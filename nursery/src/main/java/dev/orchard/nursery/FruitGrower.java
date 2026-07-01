package dev.orchard.nursery;

import dev.orchard.core.model.DevcontainerSeed;
import dev.orchard.core.model.DevfileSeed;
import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.FruitState;
import dev.orchard.core.model.LifecycleCommand;
import dev.orchard.core.model.Seed;
import dev.orchard.core.model.Seedling;
import dev.orchard.core.model.WaitFor;
import dev.orchard.nursery.event.FruitProgressEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Grows Fruit (devcontainers) on Seedlings (VMs).
 *
 * <p>Two paths are supported:
 * <ul>
 *   <li><b>CLI path (default, gated by {@code orchard.nursery.use-devcontainer-cli=true})</b> —
 *       delegates to {@link DevcontainerCli} which shells out to {@code @devcontainers/cli}
 *       installed on the Seedling. This is the only path that honours {@code seed.features()},
 *       i.e. the actual fix for issue #74.</li>
 *   <li><b>Legacy docker path</b> — raw {@code docker pull}/{@code docker build}/{@code docker run}
 *       over SSH. Retained for one release cycle behind the feature flag per spec Locked
 *       decision #12. {@code seed.features()} is silently ignored on this path.</li>
 * </ul>
 *
 * <p>{@link #pick(Seedling, Fruit)} and {@link #compost(Seedling, Fruit)} both run raw
 * {@code docker stop}/{@code docker rm -f} over SSH on either path — the CLI doesn't ship a
 * {@code down} subcommand (spec Locked decision #4).
 */
public class FruitGrower {

    private static final Logger log = LoggerFactory.getLogger(FruitGrower.class);

    private final ExecutorService executor;
    private final DevcontainerCli devcontainerCli;
    private final boolean useDevcontainerCli;
    private final ApplicationEventPublisher events;

    /**
     * Production constructor used by the Spring wiring in trellis.
     *
     * @param devcontainerCli  the CLI wrapper; may be {@code null} when the feature flag is off
     * @param useDevcontainerCli when true and {@code devcontainerCli != null}, grow() routes
     *                           to the CLI path; otherwise the legacy docker path runs
     * @param events           optional event publisher for {@link FruitProgressEvent}s; if
     *                         {@code null}, phase transitions are still logged but not broadcast
     */
    public FruitGrower(DevcontainerCli devcontainerCli, boolean useDevcontainerCli, ApplicationEventPublisher events) {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.devcontainerCli = devcontainerCli;
        this.useDevcontainerCli = useDevcontainerCli;
        this.events = events;
    }

    /**
     * Legacy no-arg constructor. Retained so existing tests and wiring keep compiling while the
     * rest of the repo is migrated to the three-arg form. Defaults to the legacy docker path.
     */
    public FruitGrower() {
        this(null, false, null);
    }


    /**
     * Grows a fruit (starts a devcontainer) on the given seedling. Routes based on both
     * the feature flag and the concrete seed type:
     *
     * <ul>
     *   <li>{@link DevcontainerSeed} + CLI flag on → devcontainer CLI path</li>
     *   <li>{@link DevcontainerSeed} + CLI flag off → legacy docker path</li>
     *   <li>{@link DevfileSeed} → docker-direct path (devcontainer CLI is not applicable)</li>
     * </ul>
     */
    public CompletableFuture<Fruit> grow(Seedling seedling, Fruit fruit) {
        return CompletableFuture.supplyAsync(() -> {
            return switch (fruit.seed()) {
                case DevfileSeed ignored -> {
                    log.info("Growing fruit {} on seedling {} (path=devfile-docker)", fruit.id(), seedling.id());
                    yield growDevfileViaDocker(seedling, fruit);
                }
                case DevcontainerSeed ignored -> {
                    boolean useCli = useDevcontainerCli && devcontainerCli != null;
                    log.info("Growing fruit {} on seedling {} (path={})", fruit.id(), seedling.id(),
                        useCli ? "devcontainer-cli" : "legacy-docker");
                    yield useCli ? growViaCli(seedling, fruit) : growViaDocker(seedling, fruit);
                }
                default -> {
                    log.warn("Unknown seed type {} for fruit {}, falling back to legacy docker",
                        fruit.seed().getClass().getSimpleName(), fruit.id());
                    yield growViaDocker(seedling, fruit);
                }
            };
        }, executor);
    }

    // --- CLI path -----------------------------------------------------------------------------

    /**
     * Grows the fruit by shelling out to {@code @devcontainers/cli} on the Seedling. This is the
     * spec-faithful path: features, lifecycle commands and waitFor are all owned by the CLI.
     */
    Fruit growViaCli(Seedling seedling, Fruit fruit) {
        DevcontainerSeed seed = devcontainerSeed(fruit);
        try {
            // initializeCommand runs on the host VM before the container starts — the CLI does
            // not own this hook (spec Locked decision #2).
            if (seed.initializeCommand() != null) {
                runLifecycleCommand(seed.initializeCommand(), cmd -> executeSsh(seedling, cmd));
            }

            PhaseTransitionFilter phaseFilter = new PhaseTransitionFilter(fruit.id(), fruit.groveId(), events);

            DevcontainerCliResult result = devcontainerCli.up(
                seedling, fruit.id(), fruit.containerName(), phaseFilter::onLine);

            // Locked decision #18 — the actual container name on the host may differ from
            // Fruit.containerName (e.g. CLI appends a suffix on regrow). Inspect and update so
            // downstream consumers (trowel grove status, GroveResponse) see reality.
            String realName;
            try {
                realName = devcontainerCli.inspectContainerName(seedling, result.containerId());
            } catch (Exception inspectFailure) {
                log.warn("Could not inspect real container name for fruit {} (containerId={}); " +
                    "keeping Fruit.containerName as-is", fruit.id(), result.containerId(), inspectFailure);
                realName = fruit.containerName();
            }

            // Best-effort port mapping fetch — failures here shouldn't fail the grow, since
            // the container is already running.
            List<Fruit.PortMapping> ports;
            try {
                ports = getPortMappings(seedling, result.containerId());
            } catch (Exception portFailure) {
                log.warn("Could not fetch port mappings for fruit {} (containerId={})",
                    fruit.id(), result.containerId(), portFailure);
                ports = List.of();
            }

            Fruit grown = rebuildWithRealName(fruit, realName)
                .withContainerDetails(result.containerId(), ports);

            // CLI ran every lifecycle command up to waitFor; if the user asked to wait for
            // postAttach, FruitGrower.attach() will flip to RIPE later.
            if (seed.effectiveWaitFor() != WaitFor.POST_ATTACH_COMMAND) {
                grown = grown.withState(FruitState.RIPE);
            }
            return grown;

        } catch (DevcontainerCli.DevcontainerCliException cliFailure) {
            CliError err = cliFailure.error();
            log.error("devcontainer CLI failed for fruit {}: message={} description={} " +
                    "disallowedFeatureId={} containerId={} didStopContainer={} learnMoreUrl={}",
                fruit.id(), err.message(), err.description(), err.disallowedFeatureId(),
                err.containerId(), err.didStopContainer(), err.learnMoreUrl());
            return fruit.withState(FruitState.ROTTED);
        } catch (Exception other) {
            log.error("Failed to grow fruit {} via devcontainer CLI", fruit.id(), other);
            if (other instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return fruit.withState(FruitState.ROTTED);
        }
    }

    /**
     * Reconstructs a Fruit replacing only its containerName. {@link Fruit} doesn't expose a
     * {@code withContainerName} helper today; rather than touch the core model from Lane C,
     * we copy the record by hand.
     */
    private static Fruit rebuildWithRealName(Fruit fruit, String newName) {
        if (newName == null || newName.equals(fruit.containerName())) {
            return fruit;
        }
        return new Fruit(
            fruit.id(), fruit.groveId(), fruit.seedlingId(),
            fruit.containerId(), newName, fruit.serviceName(), fruit.seed(),
            fruit.state(), fruit.portMappings(), fruit.buddedAt(), fruit.ripenedAt());
    }

    /**
     * Filters the raw CLI JSON line stream into a small set of phase-transition events. Only
     * publishes when the detected phase changes (spec Locked decision #19), keeping STOMP
     * traffic ~10-15 events per grow rather than 50-200 per CLI log line.
     */
    static final class PhaseTransitionFilter {
        private final UUID fruitId;
        private final UUID groveId;
        private final ApplicationEventPublisher events;
        private String lastPhase;

        PhaseTransitionFilter(UUID fruitId, UUID groveId, ApplicationEventPublisher events) {
            this.fruitId = fruitId;
            this.groveId = groveId;
            this.events = events;
        }

        void onLine(String jsonLine) {
            String phase = detectPhase(jsonLine);
            if (phase == null || phase.equals(lastPhase)) {
                return;
            }
            lastPhase = phase;
            if (events != null) {
                try {
                    events.publishEvent(new FruitProgressEvent(
                        fruitId, groveId, phase, jsonLine, System.currentTimeMillis()));
                } catch (Exception broadcastFailure) {
                    // Event broadcast is observability, not correctness — never block the grow.
                    log.warn("Failed to publish FruitProgressEvent for fruit {} phase={}",
                        fruitId, phase, broadcastFailure);
                }
            }
        }

        private String detectPhase(String jsonLine) {
            if (jsonLine.contains("Building image")) return "BUILD_START";
            if (jsonLine.contains("Successfully built")) return "BUILD_DONE";
            if (jsonLine.contains("Running install.sh")) return "FEATURE_INSTALL_START";
            if (jsonLine.contains("Container is running")) return "POST_CREATE_DONE";
            if (jsonLine.contains("\"outcome\":\"success\"")) return "READY";
            if (jsonLine.contains("\"outcome\":\"error\"")) return "ERROR";
            return null;
        }
    }

    // --- Legacy docker path -------------------------------------------------------------------

    /**
     * Legacy grow path. Identical behaviour to the pre-#74 {@code grow()} so existing tests and
     * deployments keep working until the feature flag is flipped on in production. {@code
     * seed.features()} is silently ignored here — this path predates feature support.
     */
    @Deprecated(forRemoval = true, since = "next-release")
    Fruit growViaDocker(Seedling seedling, Fruit fruit) {
        try {
            DevcontainerSeed seed = devcontainerSeed(fruit);

            if (seed.initializeCommand() != null) {
                runLifecycleCommand(seed.initializeCommand(), cmd -> executeSsh(seedling, cmd));
            }

            String containerId;
            if (seed.image() != null) {
                containerId = legacyRunFromImage(seedling, fruit);
            } else if (seed.dockerfilePath() != null) {
                containerId = legacyBuildAndRun(seedling, fruit);
            } else {
                throw new IllegalArgumentException("Seed must have either image or dockerfilePath");
            }

            List<Fruit.PortMapping> ports = getPortMappings(seedling, containerId);

            if (seed.onCreateCommand() != null) {
                runLifecycleCommand(seed.onCreateCommand(), cmd -> inContainer(seedling, containerId, cmd));
            }
            if (seed.postCreateCommand() != null) {
                runLifecycleCommand(seed.postCreateCommand(), cmd -> inContainer(seedling, containerId, cmd));
            }
            if (seed.updateContentCommand() != null) {
                runLifecycleCommand(seed.updateContentCommand(), cmd -> inContainer(seedling, containerId, cmd));
            }

            Fruit result = fruit.withContainerDetails(containerId, ports);
            if (seed.effectiveWaitFor() != WaitFor.POST_ATTACH_COMMAND) {
                result = result.withState(FruitState.RIPE);
            }

            if (seed.postStartCommand() != null) {
                runLifecycleCommand(seed.postStartCommand(), cmd -> inContainer(seedling, containerId, cmd));
            }

            return result;

        } catch (Exception e) {
            log.error("Failed to grow fruit {} via legacy docker path", fruit.id(), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return fruit.withState(FruitState.ROTTED);
        }
    }

    // --- Devfile docker path -----------------------------------------------------------------

    /**
     * Grows a fruit from a {@link DevfileSeed} by running the container image directly via
     * docker. The devcontainer CLI is not involved — devfile workspaces use the image declared
     * in the {@code container} component and are started with the workspace mounted at
     * {@code /projects} (devfile convention).
     *
     * <p>Env vars and forwarded ports from the devfile are applied. Resource limits
     * ({@code memoryLimit} / {@code cpuLimit}) are passed as docker {@code --memory} /
     * {@code --cpus} constraints when present.
     */
    Fruit growDevfileViaDocker(Seedling seedling, Fruit fruit) {
        DevfileSeed seed = devfileSeed(fruit);
        try {
            if (seed.image() == null || seed.image().isBlank()) {
                throw new IllegalArgumentException(
                    "DevfileSeed for fruit " + fruit.id() + " has no image — cannot start container");
            }

            executeSsh(seedling, "docker pull " + shellQuote(seed.image()));

            StringBuilder cmd = new StringBuilder("docker run -d");
            cmd.append(" --name ").append(shellQuote(fruit.containerName()));

            if (!seed.containerEnv().isEmpty()) {
                for (var entry : seed.containerEnv().entrySet()) {
                    cmd.append(" -e ").append(shellQuote(entry.getKey() + "=" + entry.getValue()));
                }
            }
            if (!seed.forwardPorts().isEmpty()) {
                for (String port : seed.forwardPorts()) {
                    cmd.append(" -p ").append(port).append(":").append(port);
                }
            }
            if (seed.memoryLimit() != null) {
                cmd.append(" --memory ").append(shellQuote(seed.memoryLimit()));
            }
            if (seed.cpuLimit() != null) {
                cmd.append(" --cpus ").append(shellQuote(seed.cpuLimit()));
            }

            // Mount workspace at /projects — devfile convention
            cmd.append(" -v /workspace:/projects");
            cmd.append(" -w /projects");
            cmd.append(" ").append(shellQuote(seed.image()));
            cmd.append(" sleep infinity");

            String containerId = executeSsh(seedling, cmd.toString()).trim();
            List<Fruit.PortMapping> ports = getPortMappings(seedling, containerId);

            return fruit.withContainerDetails(containerId, ports).withState(FruitState.RIPE);

        } catch (Exception e) {
            log.error("Failed to grow fruit {} via devfile docker path", fruit.id(), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return fruit.withState(FruitState.ROTTED);
        }
    }

    /**
     * Grows multiple fruits via Docker Compose on the given seedling. Legacy path; the CLI path
     * subsumes compose in a future lane (spec Locked decision #3 / #11).
     */
    @Deprecated(forRemoval = true, since = "next-release")
    public CompletableFuture<List<Fruit>> growCompose(Seedling seedling, List<Fruit> fruits, String composeFile) {
        return growCompose(seedling, fruits, List.of(composeFile));
    }

    /**
     * Grows multiple fruits via Docker Compose on the given seedling.
     * Accepts all compose files in override order (issue #32 — {@code dockerComposeFiles}).
     * Legacy path; the CLI path subsumes compose in a future lane.
     */
    @Deprecated(forRemoval = true, since = "next-release")
    public CompletableFuture<List<Fruit>> growCompose(Seedling seedling, List<Fruit> fruits, List<String> composeFiles) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("Growing {} fruits via Docker Compose on seedling {}", fruits.size(), seedling.id());

                // Build "-f file1 -f file2 ..." from all compose files (issue #32)
                StringBuilder fileArgs = new StringBuilder();
                for (String cf : composeFiles) {
                    String path = cf.startsWith("/") ? cf : "/workspace/" + cf;
                    fileArgs.append(" -f ").append(shellQuote(path));
                }

                // Determine services to start: runServices union primary service names
                List<String> serviceNames = fruits.stream()
                    .map(f -> f.serviceName() != null ? f.serviceName() : f.containerName())
                    .distinct()
                    .toList();

                // If the seed has runServices, pass them explicitly alongside primary services
                String upServicesArg = "";
                if (!fruits.isEmpty() && fruits.get(0).seed() instanceof DevcontainerSeed dcs
                        && !dcs.runServices().isEmpty()) {
                    java.util.LinkedHashSet<String> allServices = new java.util.LinkedHashSet<>(serviceNames);
                    allServices.addAll(dcs.runServices());
                    upServicesArg = " " + allServices.stream()
                        .map(FruitGrower::shellQuote)
                        .collect(java.util.stream.Collectors.joining(" "));
                }

                executeSsh(seedling, "docker compose" + fileArgs + " up -d" + upServicesArg);

                String psOutput = executeSsh(seedling,
                    "docker compose" + fileArgs + " ps --format '{{.ID}}|{{.Service}}|{{.Name}}'");

                List<Fruit> grownFruits = new ArrayList<>();
                for (Fruit fruit : fruits) {
                    String serviceName = fruit.serviceName() != null
                        ? fruit.serviceName() : fruit.containerName();

                    String containerId = null;
                    for (String line : psOutput.split("\n")) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty()) continue;
                        String[] parts = trimmed.split("\\|");
                        if (parts.length >= 2 && parts[1].trim().equals(serviceName)) {
                            containerId = parts[0].trim();
                            break;
                        }
                    }

                    if (containerId != null) {
                        List<Fruit.PortMapping> ports = getPortMappings(seedling, containerId);
                        if (fruit.seed() instanceof DevcontainerSeed s) {
                            String cid = containerId;
                            if (s.onCreateCommand() != null) {
                                runLifecycleCommand(s.onCreateCommand(), cmd -> inContainer(seedling, cid, cmd));
                            }
                            if (s.postCreateCommand() != null) {
                                runLifecycleCommand(s.postCreateCommand(), cmd -> inContainer(seedling, cid, cmd));
                            }
                        }
                        grownFruits.add(fruit
                            .withContainerDetails(containerId, ports)
                            .withState(FruitState.RIPE));
                    } else {
                        log.warn("No container found for service '{}' in compose output", serviceName);
                        grownFruits.add(fruit.withState(FruitState.ROTTED));
                    }
                }

                return grownFruits;

            } catch (Exception e) {
                log.error("Failed to grow compose stack on seedling {}", seedling.id(), e);
                return fruits.stream()
                    .map(f -> f.withState(FruitState.ROTTED))
                    .toList();
            }
        }, executor);
    }

    /**
     * Picks a fruit (stops the container). Same SSH semantics on both paths — the CLI has no
     * {@code down} subcommand per spec Locked decision #4.
     */
    public CompletableFuture<Fruit> pick(Seedling seedling, Fruit fruit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("Picking fruit {}", fruit.id());
                if (fruit.containerId() != null) {
                    executeSsh(seedling, "docker stop " + fruit.containerId());
                }
                return fruit.withState(FruitState.PICKED);
            } catch (Exception e) {
                log.error("Failed to pick fruit {}", fruit.id(), e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return fruit;
            }
        }, executor);
    }

    /**
     * Composts a fruit (removes the container entirely).
     */
    public CompletableFuture<Void> compost(Seedling seedling, Fruit fruit) {
        return CompletableFuture.runAsync(() -> {
            try {
                log.info("Composting fruit {}", fruit.id());
                if (fruit.containerId() != null) {
                    executeSsh(seedling, "docker rm -f " + fruit.containerId());
                }
            } catch (Exception e) {
                log.error("Failed to compost fruit {}", fruit.id(), e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }, executor);
    }

    public CompletableFuture<Fruit> attach(Seedling seedling, Fruit fruit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                DevcontainerSeed seed = devcontainerSeed(fruit);
                if (seed.postAttachCommand() != null) {
                    if (useDevcontainerCli && devcontainerCli != null) {
                        runLifecycleCommand(seed.postAttachCommand(),
                            cmd -> devcontainerCli.exec(seedling, cmd));
                    } else {
                        runLifecycleCommand(seed.postAttachCommand(),
                            cmd -> inContainer(seedling, fruit.containerId(), cmd));
                    }
                }
                if (seed.waitFor() == WaitFor.POST_ATTACH_COMMAND) {
                    return fruit.withState(FruitState.RIPE);
                }
                return fruit;
            } catch (Exception e) {
                log.error("Failed to attach to fruit {}", fruit.id(), e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return fruit;
            }
        }, executor);
    }

    public void shutdown() {
        executor.shutdown();
    }

    // --- Legacy docker helpers (deprecated, kept for one release) -----------------------------

    private static DevcontainerSeed devcontainerSeed(Fruit fruit) {
        if (fruit.seed() instanceof DevcontainerSeed s) return s;
        throw new IllegalArgumentException("Fruit " + fruit.id() + " has no DevcontainerSeed");
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static DevfileSeed devfileSeed(Fruit fruit) {
        if (fruit.seed() instanceof DevfileSeed s) return s;
        throw new IllegalArgumentException("Fruit " + fruit.id() + " has no DevfileSeed");
    }

    @Deprecated(forRemoval = true, since = "next-release")
    private String legacyRunFromImage(Seedling seedling, Fruit fruit) throws IOException, InterruptedException {
        DevcontainerSeed seed = devcontainerSeed(fruit);

        executeSsh(seedling, "docker pull " + shellQuote(seed.image()));

        StringBuilder cmd = new StringBuilder("docker run -d");
        cmd.append(" --name ").append(shellQuote(fruit.containerName()));

        // Runtime flags from spec (issue #29)
        if (Boolean.TRUE.equals(seed.privileged())) {
            cmd.append(" --privileged");
        }
        if (Boolean.TRUE.equals(seed.init())) {
            cmd.append(" --init");
        }
        for (String cap : seed.capAdd()) {
            cmd.append(" --cap-add ").append(shellQuote(cap));
        }
        for (String opt : seed.securityOpt()) {
            cmd.append(" --security-opt ").append(shellQuote(opt));
        }

        // containerEnv
        for (var entry : seed.containerEnv().entrySet()) {
            cmd.append(" -e ").append(shellQuote(entry.getKey() + "=" + entry.getValue()));
        }

        // forwardPorts
        for (String port : seed.forwardPorts()) {
            cmd.append(" -p ").append(port).append(":").append(port);
        }

        // extra mounts (issue #29)
        for (String mount : seed.mounts()) {
            cmd.append(" --mount ").append(shellQuote(mount));
        }

        // extra docker run args (issue #29)
        for (String arg : seed.runArgs()) {
            cmd.append(" ").append(arg);
        }

        // workspace mount / folder (issue #29)
        if (seed.workspaceMount() != null) {
            cmd.append(" --mount ").append(shellQuote(seed.workspaceMount()));
        } else {
            cmd.append(" -v /workspace:/workspace");
        }
        String workdir = seed.workspaceFolder() != null ? seed.workspaceFolder() : "/workspace";
        cmd.append(" -w ").append(shellQuote(workdir));

        cmd.append(" ").append(shellQuote(seed.image()));
        cmd.append(" sleep infinity");

        return executeSsh(seedling, cmd.toString()).trim();
    }

    @Deprecated(forRemoval = true, since = "next-release")
    private String legacyBuildAndRun(Seedling seedling, Fruit fruit) throws IOException, InterruptedException {
        DevcontainerSeed seed = devcontainerSeed(fruit);

        String imageTag = "orchard-fruit-" + fruit.id().toString().substring(0, 8);
        StringBuilder buildCmd = new StringBuilder("docker build");
        buildCmd.append(" -t ").append(shellQuote(imageTag));

        // build.args
        for (var entry : seed.buildArgs().entrySet()) {
            buildCmd.append(" --build-arg ")
                .append(shellQuote(entry.getKey() + "=" + entry.getValue()));
        }
        // build.target (issue #31)
        if (seed.buildTarget() != null) {
            buildCmd.append(" --target ").append(shellQuote(seed.buildTarget()));
        }
        // build.cacheFrom (issue #31)
        for (String cache : seed.buildCacheFrom()) {
            buildCmd.append(" --cache-from ").append(shellQuote(cache));
        }
        // build.options — verbatim flags (issue #31)
        for (String opt : seed.buildOptions()) {
            buildCmd.append(" ").append(opt);
        }

        buildCmd.append(" -f ").append(shellQuote("/workspace/" + seed.dockerfilePath()));

        // build.context (issue #31) — relative to /workspace
        String context = seed.buildContext() != null
            ? "/workspace/" + seed.buildContext()
            : "/workspace";
        buildCmd.append(" ").append(shellQuote(context));

        executeSsh(seedling, buildCmd.toString());

        Seed imageOnlySeed = DevcontainerSeed.devcontainer()
            .name(seed.name())
            .image(imageTag)
            .containerEnv(seed.containerEnv())
            .forwardPorts(seed.forwardPorts())
            .mounts(seed.mounts())
            .runArgs(seed.runArgs())
            .workspaceFolder(seed.workspaceFolder())
            .workspaceMount(seed.workspaceMount())
            .privileged(seed.privileged())
            .init(seed.init())
            .capAdd(seed.capAdd())
            .securityOpt(seed.securityOpt())
            .build();

        Fruit imageFruit = new Fruit(
            fruit.id(), fruit.groveId(), fruit.seedlingId(),
            null, fruit.containerName(), fruit.serviceName(), imageOnlySeed,
            fruit.state(), fruit.portMappings(), fruit.buddedAt(), fruit.ripenedAt()
        );

        return legacyRunFromImage(seedling, imageFruit);
    }

    static List<Fruit.PortMapping> parsePortOutput(String output) {
        List<Fruit.PortMapping> mappings = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (line.contains("->")) {
                String[] parts = line.split(" -> ");
                String hostAddress = parts[1].trim();
                if (hostAddress.startsWith("[")) {
                    continue;
                }
                String[] containerPart = parts[0].split("/");
                int containerPort = Integer.parseInt(containerPart[0].trim());
                String protocol = containerPart[1].trim();
                int hostPort = Integer.parseInt(hostAddress.substring(hostAddress.lastIndexOf(':') + 1));
                mappings.add(new Fruit.PortMapping(containerPort, hostPort, protocol));
            }
        }
        return mappings;
    }

    private List<Fruit.PortMapping> getPortMappings(Seedling seedling, String containerId)
            throws IOException, InterruptedException {
        String output = executeSsh(seedling,
            "docker port " + containerId + " 2>/dev/null || echo ''");
        return parsePortOutput(output);
    }

    private void inContainer(Seedling seedling, String containerId, String command)
            throws IOException, InterruptedException {
        executeSsh(seedling, "docker exec " + containerId + " /bin/sh -c '" + command + "'");
    }

    private String executeSsh(Seedling seedling, String command) throws IOException, InterruptedException {
        return new SshExecutor(seedling).execute(command);
    }

    @FunctionalInterface
    private interface CommandStep {
        void run(String command) throws IOException, InterruptedException;
    }

    private void runLifecycleCommand(LifecycleCommand cmd, CommandStep step)
            throws IOException, InterruptedException {
        switch (cmd) {
            case LifecycleCommand.Sequential s ->
                step.run(String.join(" ", s.args()));
            case LifecycleCommand.Parallel p -> {
                List<CompletableFuture<Void>> futures = p.steps().values().stream()
                    .map(args -> CompletableFuture.runAsync(() -> {
                        try { step.run(String.join(" ", args)); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    }, executor))
                    .toList();
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }
        }
    }
}
