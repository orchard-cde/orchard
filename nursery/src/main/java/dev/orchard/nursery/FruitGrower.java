package dev.orchard.nursery;

import dev.orchard.core.model.DevcontainerSeed;
import dev.orchard.core.model.DevfileSeed;
import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.FruitState;
import dev.orchard.core.model.LifecycleCommand;
import dev.orchard.core.model.Seed;
import dev.orchard.core.model.WaitFor;
import dev.orchard.nursery.event.FruitProgressEvent;
import dev.orchard.vine.CommandRunner;
import dev.orchard.vine.ContainerExec;
import dev.orchard.vine.ContainerLabels;
import dev.orchard.vine.ExecTarget;
import dev.orchard.vine.ShellQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Grows Fruit (devcontainers) inside a substrate reached via an injected {@link CommandRunner}.
 *
 * <p>Two paths are supported:
 * <ul>
 *   <li><b>CLI path (default, gated by {@code orchard.nursery.use-devcontainer-cli=true})</b> —
 *       delegates to {@link DevcontainerCli} which shells out to {@code @devcontainers/cli}
 *       inside the substrate. This is the only path that honours {@code seed.features()},
 *       i.e. the actual fix for issue #74.</li>
 *   <li><b>Legacy docker path</b> — raw {@code docker pull}/{@code docker build}/{@code docker run}
 *       over the runner. Retained for one release cycle behind the feature flag. {@code seed.features()} is silently ignored on this path.</li>
 * </ul>
 *
 * <p>{@link #pick}, {@link #ripen} and {@link #compost} run raw docker commands over the runner on
 * either path — the CLI doesn't ship a {@code down} subcommand. A compose fruit's project is found
 * by its {@link ContainerLabels#COMPOSE_PROJECT} label rather than through the compose CLI, which
 * under podman-compose needs the compose file: {@code pick} and {@code ripen} stop and start every
 * container of the project, and {@code compost} removes its containers, networks and volumes. A
 * single-container fruit is stopped, started and removed by id.
 */
public class FruitGrower {

    private static final Logger log = LoggerFactory.getLogger(FruitGrower.class);

    private static final long ENGINE_TIMEOUT_SECONDS = 120;

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
     * Grows a fruit (starts a devcontainer) against the given workspace. Routes based on both
     * the feature flag and the concrete seed type:
     *
     * <ul>
     *   <li>{@link DevcontainerSeed} + CLI flag on → devcontainer CLI path</li>
     *   <li>{@link DevcontainerSeed} + CLI flag off → legacy docker path</li>
     *   <li>{@link DevfileSeed} → docker-direct path (devcontainer CLI is not applicable)</li>
     * </ul>
     */
    public CompletableFuture<Fruit> grow(ExecTarget target, Fruit fruit) {
        return CompletableFuture.supplyAsync(() -> {
            return switch (fruit.seed()) {
                case DevfileSeed ignored -> {
                    log.info("Growing fruit {} on target {} (path=devfile-docker)", fruit.id(), target.targetId());
                    yield growDevfileViaDocker(target, fruit);
                }
                case DevcontainerSeed ignored -> {
                    boolean useCli = cliEnabled();
                    log.info("Growing fruit {} on target {} (path={})", fruit.id(), target.targetId(),
                        useCli ? "devcontainer-cli" : "legacy-docker");
                    yield useCli ? growViaCli(target, fruit)
                                  : growViaDocker(target, fruit);
                }
                default -> {
                    log.warn("Unknown seed type {} for fruit {}, falling back to legacy docker",
                        fruit.seed().getClass().getSimpleName(), fruit.id());
                    yield growViaDocker(target, fruit);
                }
            };
        }, executor);
    }


    /**
     * Grows the fruit by shelling out to {@code @devcontainers/cli} via the runner. This is the
     * spec-faithful path: features, lifecycle commands and waitFor are all owned by the CLI.
     */
    Fruit growViaCli(ExecTarget target, Fruit fruit) {
        CommandRunner runner = target.runner();
        DevcontainerSeed seed = devcontainerSeed(fruit);
        try {
            // initializeCommand runs on the host before the container starts; the CLI does
            // not own this hook.
            if (seed.initializeCommand() != null) {
                runLifecycleCommand(seed.initializeCommand(),
                    cmd -> executeSsh(runner, cmd));
            }

            PhaseTransitionFilter phaseFilter = new PhaseTransitionFilter(fruit.id(), fruit.groveId(), events);

            DevcontainerCliResult result = devcontainerCli.up(
                target, fruit.id(), fruit.containerName(), phaseFilter::onLine);

            // The actual container name on the host may differ from
            // Fruit.containerName (e.g. CLI appends a suffix on regrow). Inspect and update so
            // downstream consumers (trowel grove status, GroveResponse) see reality.
            String realName;
            try {
                realName = devcontainerCli.inspectContainerName(target, result.containerId());
            } catch (Exception inspectFailure) {
                log.warn("Could not inspect real container name for fruit {} (containerId={}); " +
                    "keeping Fruit.containerName as-is", fruit.id(), result.containerId(), inspectFailure);
                realName = fruit.containerName();
            }

            // Best-effort port mapping fetch — failures here shouldn't fail the grow, since
            // the container is already running.
            List<Fruit.PortMapping> ports;
            try {
                ports = getPortMappings(target, result.containerId());
            } catch (Exception portFailure) {
                log.warn("Could not fetch port mappings for fruit {} (containerId={})",
                    fruit.id(), result.containerId(), portFailure);
                ports = List.of();
            }

            trustWorkspace(target, result.containerId(), result.remoteWorkspaceFolder());

            Fruit grown = rebuildWithRealName(fruit, realName)
                .withContainerDetails(result.containerId(), ports)
                .withComposeProjectName(result.composeProjectName())
                .withRemoteEnv(result.remoteWorkspaceFolder(), result.remoteUser());

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
     * Reconstructs a Fruit replacing only its containerName. Fruit has no
     * {@code withContainerName} helper, so the record is copied by hand.
     */
    private static Fruit rebuildWithRealName(Fruit fruit, String newName) {
        if (newName == null || newName.equals(fruit.containerName())) {
            return fruit;
        }
        return new Fruit(
            fruit.id(), fruit.groveId(), fruit.plotId(),
            fruit.containerId(), newName, fruit.serviceName(), fruit.composeProjectName(),
            fruit.workspaceFolder(), fruit.remoteUser(), fruit.seed(),
            fruit.state(), fruit.portMappings(), fruit.buddedAt(), fruit.ripenedAt());
    }

    /**
     * Filters the raw CLI JSON line stream into a small set of phase-transition events. Only
     * publishes when the detected phase changes, keeping STOMP
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


    /**
     * Legacy grow path. Identical behaviour to the pre-#74 {@code grow()} so existing tests and
     * deployments keep working until the feature flag is flipped on in production. {@code
     * seed.features()} is silently ignored here — this path predates feature support.
     */
    @Deprecated(forRemoval = true, since = "next-release")
    Fruit growViaDocker(ExecTarget target, Fruit fruit) {
        CommandRunner runner = target.runner();
        try {
            DevcontainerSeed seed = devcontainerSeed(fruit);

            if (seed.initializeCommand() != null) {
                runLifecycleCommand(seed.initializeCommand(),
                    cmd -> executeSsh(runner, cmd));
            }

            String containerId;
            if (seed.image() != null) {
                containerId = legacyRunFromImage(target, fruit);
            } else if (seed.dockerfilePath() != null) {
                containerId = legacyBuildAndRun(target, fruit);
            } else {
                throw new IllegalArgumentException("Seed must have either image or dockerfilePath");
            }

            List<Fruit.PortMapping> ports = getPortMappings(target, containerId);
            trustWorkspace(target, containerId, seed.workspaceFolder() != null ? seed.workspaceFolder() : "/workspace");

            if (seed.onCreateCommand() != null) {
                runLifecycleCommand(seed.onCreateCommand(),
                    cmd -> inContainer(target, containerId, cmd));
            }
            if (seed.postCreateCommand() != null) {
                runLifecycleCommand(seed.postCreateCommand(),
                    cmd -> inContainer(target, containerId, cmd));
            }
            if (seed.updateContentCommand() != null) {
                runLifecycleCommand(seed.updateContentCommand(),
                    cmd -> inContainer(target, containerId, cmd));
            }

            Fruit result = fruit.withContainerDetails(containerId, ports)
                .withRemoteEnv(seed.workspaceFolder() != null ? seed.workspaceFolder() : "/workspace", null);
            if (seed.effectiveWaitFor() != WaitFor.POST_ATTACH_COMMAND) {
                result = result.withState(FruitState.RIPE);
            }

            if (seed.postStartCommand() != null) {
                runLifecycleCommand(seed.postStartCommand(),
                    cmd -> inContainer(target, containerId, cmd));
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


    /**
     * Grows a fruit from a {@link DevfileSeed} by running the container image directly via
     * docker. The devcontainer CLI is not involved — devfile workspaces use the image declared
     * in the {@code container} component and are started with the workspace mounted at
     * {@code /projects} (devfile convention).
     *
     * <p>Env vars and forwarded ports from the devfile are applied. Resource limits
     * ({@code memoryLimit} / {@code cpuLimit}) are passed as docker {@code --memory} /
     * {@code --cpus} constraints when present.
     *
     * <p>{@code preStartCommand} (devfile {@code events.preStart}) runs over the runner before
     * {@code docker pull}/{@code docker run}. {@code postStartCommand} (devfile {@code
     * events.postStart}) runs inside the container via {@code docker exec} immediately after it
     * starts, before the fruit is reported {@code RIPE} — both reuse the same {@link
     * LifecycleCommand}/{@code runLifecycleCommand} primitive the devcontainer hooks use, with no
     * translation between devfile and devcontainer event names.
     */
    Fruit growDevfileViaDocker(ExecTarget target, Fruit fruit) {
        CommandRunner runner = target.runner();
        String cli = target.runtime().cli();
        DevfileSeed seed = devfileSeed(fruit);
        try {
            if (seed.image() == null || seed.image().isBlank()) {
                throw new IllegalArgumentException(
                    "DevfileSeed for fruit " + fruit.id() + " has no image — cannot start container");
            }

            // devfile events.preStart — runs on the host, before the container starts.
            if (seed.preStartCommand() != null) {
                runLifecycleCommand(seed.preStartCommand(),
                    cmd -> executeSsh(runner, cmd));
            }

            executeSsh(runner, cli + " pull " + ShellQuote.quote(seed.image()));

            StringBuilder cmd = new StringBuilder(cli + " run -d");
            cmd.append(" --name ").append(ShellQuote.quote(fruit.containerName()));
            cmd.append(" --label ").append(ShellQuote.quote(ContainerLabels.label(target.targetId())));

            if (!seed.containerEnv().isEmpty()) {
                for (var entry : seed.containerEnv().entrySet()) {
                    cmd.append(" -e ").append(ShellQuote.quote(entry.getKey() + "=" + entry.getValue()));
                }
            }
            if (!seed.forwardPorts().isEmpty()) {
                for (String port : seed.forwardPorts()) {
                    cmd.append(" -p ").append(ShellQuote.quote(port + ":" + port));
                }
            }
            if (seed.memoryLimit() != null) {
                cmd.append(" --memory ").append(ShellQuote.quote(seed.memoryLimit()));
            }
            if (seed.cpuLimit() != null) {
                cmd.append(" --cpus ").append(ShellQuote.quote(seed.cpuLimit()));
            }

            // Mount workspace at /projects — devfile convention
            cmd.append(" -v ").append(ShellQuote.quote(target.workspacePath())).append(":/projects");
            cmd.append(" -w /projects");
            cmd.append(" ").append(ShellQuote.quote(seed.image()));
            cmd.append(" sleep infinity");

            String containerId = executeSsh(runner, cmd.toString()).trim();
            List<Fruit.PortMapping> ports = getPortMappings(target, containerId);

            // Record container details before executing lifecycle commands so the fruit
            // retains a containerId reference for cleanup even if a command fails.
            fruit = fruit.withContainerDetails(containerId, ports).withRemoteEnv("/projects", null);
            trustWorkspace(target, containerId, "/projects");

            // devfile events.postStart — runs inside the container, after it starts.
            if (seed.postStartCommand() != null) {
                runLifecycleCommand(seed.postStartCommand(),
                    c -> inContainer(target, containerId, c));
            }

            return fruit.withState(FruitState.RIPE);

        } catch (Exception e) {
            log.error("Failed to grow fruit {} via devfile docker path", fruit.id(), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return fruit.withState(FruitState.ROTTED);
        }
    }

    /**
     * Grows multiple fruits via Docker Compose against the given workspace. Legacy path; the CLI
     * path is expected to subsume compose.
     */
    @Deprecated(forRemoval = true, since = "next-release")
    public CompletableFuture<List<Fruit>> growCompose(ExecTarget target, List<Fruit> fruits, String composeFile) {
        return growCompose(target, fruits, List.of(composeFile));
    }

    /**
     * Grows multiple fruits via Docker Compose against the given workspace.
     * Accepts all compose files in override order (issue #32 — {@code dockerComposeFiles}).
     * Legacy path; the CLI path subsumes compose in a future lane.
     */
    @Deprecated(forRemoval = true, since = "next-release")
    public CompletableFuture<List<Fruit>> growCompose(ExecTarget target, List<Fruit> fruits,
                                                        List<String> composeFiles) {
        return CompletableFuture.supplyAsync(() -> {
            CommandRunner runner = target.runner();
            String cli = target.runtime().cli();
            try {
                log.info("Growing {} fruits via Docker Compose on target {}", fruits.size(), target.targetId());

                // Build "-f file1 -f file2 ..." from all compose files (issue #32)
                StringBuilder fileArgs = new StringBuilder();
                for (String cf : composeFiles) {
                    String path = cf.startsWith("/") ? cf : target.workspacePath() + "/" + cf;
                    fileArgs.append(" -f ").append(ShellQuote.quote(path));
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
                        .map(ShellQuote::quote)
                        .collect(java.util.stream.Collectors.joining(" "));
                }

                executeSsh(runner, cli + " compose" + fileArgs + " up -d" + upServicesArg);

                String psOutput = executeSsh(runner,
                    cli + " compose" + fileArgs + " ps --format '{{.ID}}|{{.Service}}|{{.Name}}'");

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
                        List<Fruit.PortMapping> ports = getPortMappings(target, containerId);
                        if (fruit.seed() instanceof DevcontainerSeed s) {
                            String cid = containerId;
                            if (s.onCreateCommand() != null) {
                                runLifecycleCommand(s.onCreateCommand(),
                                    cmd -> inContainer(target, cid, cmd));
                            }
                            if (s.postCreateCommand() != null) {
                                runLifecycleCommand(s.postCreateCommand(),
                                    cmd -> inContainer(target, cid, cmd));
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
                log.error("Failed to grow compose stack on target {}", target.targetId(), e);
                return fruits.stream()
                    .map(f -> f.withState(FruitState.ROTTED))
                    .toList();
            }
        }, executor);
    }

    /**
     * Picks a fruit (stops its containers without removing them). A compose fruit stops the whole
     * project. Completes exceptionally if the stop fails.
     */
    public CompletableFuture<Fruit> pick(ExecTarget target, Fruit fruit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("Picking fruit {}", fruit.id());
                lifecycle(target, fruit, "stop");
                return fruit.withState(FruitState.PICKED);
            } catch (Exception e) {
                log.error("Failed to pick fruit {}", fruit.id(), e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new CompletionException(e);
            }
        }, executor);
    }

    /**
     * Ripens a picked fruit: starts the same containers again, then re-runs the post-start hook the
     * way it ran when the fruit was grown — through the devcontainer CLI for CLI-grown devcontainers,
     * via {@code docker exec} otherwise. Completes exceptionally if the containers do not start; a
     * failing post-start hook is logged and the fruit is still returned {@link FruitState#RIPE}.
     */
    public CompletableFuture<Fruit> ripen(ExecTarget target, Fruit fruit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("Ripening fruit {}", fruit.id());
                lifecycle(target, fruit, "start");
                runPostStart(target, fruit);
                return fruit.withState(FruitState.RIPE);
            } catch (Exception e) {
                log.error("Failed to ripen fruit {}", fruit.id(), e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new CompletionException(e);
            }
        }, executor);
    }

    /**
     * Runs {@code stop} or {@code start} on the fruit's containers. A compose project is started in
     * up to one pass per container, since a container sharing another's network namespace starts
     * only once that one runs and the engine lists the project in no dependency order.
     */
    private void lifecycle(ExecTarget target, Fruit fruit, String verb) throws IOException, InterruptedException {
        CommandRunner runner = target.runner();
        String cli = target.runtime().cli();
        if (fruit.composeProjectName() == null) {
            if (fruit.containerId() != null) {
                executeSsh(runner, cli + " " + verb + " " + ShellQuote.quote(fruit.containerId()));
            }
            return;
        }
        List<String> ids = ContainerLabels.composeContainerIds(runner, cli, fruit.composeProjectName(), true,
            ENGINE_TIMEOUT_SECONDS);
        if (ids.isEmpty()) {
            if (verb.equals("start")) {
                throw new IOException("compose project " + fruit.composeProjectName() + " has no containers");
            }
            return;
        }
        String command = cli + " " + verb + " " + ShellQuote.join(ids);
        int passes = verb.equals("start") ? ids.size() : 1;
        for (int pass = 1; ; pass++) {
            try {
                runner.execute(command, ENGINE_TIMEOUT_SECONDS);
                return;
            } catch (IOException e) {
                if (pass >= passes) {
                    throw e;
                }
            }
        }
    }

    private boolean cliEnabled() {
        return useDevcontainerCli && devcontainerCli != null;
    }

    private void runPostStart(ExecTarget target, Fruit fruit) throws InterruptedException {
        try {
            if (fruit.seed() instanceof DevcontainerSeed && cliEnabled()) {
                devcontainerCli.runUserCommands(target, fruit.containerId());
                return;
            }
            LifecycleCommand postStart = switch (fruit.seed()) {
                case DevcontainerSeed s -> s.postStartCommand();
                case DevfileSeed s -> s.postStartCommand();
                default -> null;
            };
            if (postStart != null) {
                runLifecycleCommand(postStart, cmd -> inContainer(target, fruit.containerId(), cmd));
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("postStartCommand for fruit {} failed; its container is running", fruit.id(), e);
        }
    }

    /**
     * Composts a fruit: tears down its whole compose project with its volumes, or removes its
     * single container.
     */
    public CompletableFuture<Void> compost(ExecTarget target, Fruit fruit) {
        return CompletableFuture.runAsync(() -> {
            try {
                log.info("Composting fruit {}", fruit.id());
                String cli = target.runtime().cli();
                if (fruit.composeProjectName() != null) {
                    ContainerLabels.removeComposeProject(target.runner(), cli, fruit.composeProjectName(),
                        ENGINE_TIMEOUT_SECONDS);
                } else if (fruit.containerId() != null) {
                    executeSsh(target.runner(), cli + " rm -f " + ShellQuote.quote(fruit.containerId()));
                }
            } catch (Exception e) {
                log.error("Failed to compost fruit {}", fruit.id(), e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new CompletionException(e);
            }
        }, executor);
    }

    public CompletableFuture<Fruit> attach(ExecTarget target, Fruit fruit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                DevcontainerSeed seed = devcontainerSeed(fruit);
                if (seed.postAttachCommand() != null) {
                    if (cliEnabled()) {
                        runLifecycleCommand(seed.postAttachCommand(),
                            cmd -> devcontainerCli.exec(target, cmd));
                    } else {
                        runLifecycleCommand(seed.postAttachCommand(),
                            cmd -> inContainer(target, fruit.containerId(), cmd));
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


    private static DevcontainerSeed devcontainerSeed(Fruit fruit) {
        if (fruit.seed() instanceof DevcontainerSeed s) return s;
        throw new IllegalArgumentException("Fruit " + fruit.id() + " has no DevcontainerSeed");
    }

    private static DevfileSeed devfileSeed(Fruit fruit) {
        if (fruit.seed() instanceof DevfileSeed s) return s;
        throw new IllegalArgumentException("Fruit " + fruit.id() + " has no DevfileSeed");
    }

    @Deprecated(forRemoval = true, since = "next-release")
    private String legacyRunFromImage(ExecTarget target, Fruit fruit)
            throws IOException, InterruptedException {
        DevcontainerSeed seed = devcontainerSeed(fruit);
        String cli = target.runtime().cli();

        executeSsh(target.runner(), cli + " pull " + ShellQuote.quote(seed.image()));

        StringBuilder cmd = new StringBuilder(cli + " run -d");
        cmd.append(" --name ").append(ShellQuote.quote(fruit.containerName()));
        cmd.append(" --label ").append(ShellQuote.quote(ContainerLabels.label(target.targetId())));

        // Runtime flags (issue #29)
        if (Boolean.TRUE.equals(seed.privileged())) {
            cmd.append(" --privileged");
        }
        if (Boolean.TRUE.equals(seed.init())) {
            cmd.append(" --init");
        }
        for (String cap : seed.capAdd()) {
            cmd.append(" --cap-add ").append(ShellQuote.quote(cap));
        }
        for (String opt : seed.securityOpt()) {
            cmd.append(" --security-opt ").append(ShellQuote.quote(opt));
        }

        for (var entry : seed.containerEnv().entrySet()) {
            cmd.append(" -e ").append(ShellQuote.quote(entry.getKey() + "=" + entry.getValue()));
        }

        for (String port : seed.forwardPorts()) {
            cmd.append(" -p ").append(ShellQuote.quote(port + ":" + port));
        }

        // extra mounts (issue #29)
        for (String mount : seed.mounts()) {
            cmd.append(" --mount ").append(ShellQuote.quote(mount));
        }

        // extra docker run args (issue #29)
        for (String arg : seed.runArgs()) {
            cmd.append(" ").append(ShellQuote.quote(arg));
        }

        // workspace mount / folder (issue #29)
        if (seed.workspaceMount() != null) {
            cmd.append(" --mount ").append(ShellQuote.quote(seed.workspaceMount()));
        } else {
            cmd.append(" -v ").append(ShellQuote.quote(target.workspacePath())).append(":/workspace");
        }
        String workdir = seed.workspaceFolder() != null ? seed.workspaceFolder() : "/workspace";
        cmd.append(" -w ").append(ShellQuote.quote(workdir));

        cmd.append(" ").append(ShellQuote.quote(seed.image()));
        cmd.append(" sleep infinity");

        return executeSsh(target.runner(), cmd.toString()).trim();
    }

    @Deprecated(forRemoval = true, since = "next-release")
    private String legacyBuildAndRun(ExecTarget target, Fruit fruit)
            throws IOException, InterruptedException {
        DevcontainerSeed seed = devcontainerSeed(fruit);
        String workspace = target.workspacePath();

        String imageTag = "orchard-fruit-" + fruit.id().toString().substring(0, 8);
        StringBuilder buildCmd = new StringBuilder(target.runtime().cli() + " build");
        buildCmd.append(" -t ").append(ShellQuote.quote(imageTag));

        for (var entry : seed.buildArgs().entrySet()) {
            buildCmd.append(" --build-arg ")
                .append(ShellQuote.quote(entry.getKey() + "=" + entry.getValue()));
        }
        // build.target (issue #31)
        if (seed.buildTarget() != null) {
            buildCmd.append(" --target ").append(ShellQuote.quote(seed.buildTarget()));
        }
        // build.cacheFrom (issue #31)
        for (String cache : seed.buildCacheFrom()) {
            buildCmd.append(" --cache-from ").append(ShellQuote.quote(cache));
        }
        // build.options — one argument per element (issue #31)
        for (String opt : seed.buildOptions()) {
            buildCmd.append(" ").append(ShellQuote.quote(opt));
        }

        buildCmd.append(" -f ").append(ShellQuote.quote(workspace + "/" + seed.dockerfilePath()));

        // build.context (issue #31) — relative to the workspace
        String context = seed.buildContext() != null
            ? workspace + "/" + seed.buildContext()
            : workspace;
        buildCmd.append(" ").append(ShellQuote.quote(context));

        executeSsh(target.runner(), buildCmd.toString());

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
            fruit.id(), fruit.groveId(), fruit.plotId(),
            null, fruit.containerName(), fruit.serviceName(), fruit.composeProjectName(),
            fruit.workspaceFolder(), fruit.remoteUser(), imageOnlySeed,
            fruit.state(), fruit.portMappings(), fruit.buddedAt(), fruit.ripenedAt()
        );

        return legacyRunFromImage(target, imageFruit);
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

    private List<Fruit.PortMapping> getPortMappings(ExecTarget target, String containerId)
            throws IOException, InterruptedException {
        String output = executeSsh(target.runner(), target.runtime().cli() + " port " + ShellQuote.quote(containerId) + " 2>/dev/null || echo ''");
        return parsePortOutput(output);
    }

    /**
     * Marks the workspace as a git {@code safe.directory} system-wide in the fruit, so git works for any user
     * even when the bind mount's ownership doesn't match (macOS file sharing reports the mount root as root).
     * Best-effort: an image without git, or a failure here, never fails the grow.
     */
    private void trustWorkspace(ExecTarget target, String containerId, String workspaceFolder)
            throws InterruptedException {
        if (workspaceFolder == null) {
            return;
        }
        String folder = ShellQuote.quote(workspaceFolder);
        String command = "command -v git >/dev/null 2>&1 || exit 0; "
            + "git config --system --get-all safe.directory 2>/dev/null | grep -qxF " + folder
            + " || git config --system --add safe.directory " + folder;
        try {
            executeSsh(target.runner(),
                ShellQuote.join(ContainerExec.of(target.runtime().cli(), containerId).user("root").shell(command).argv()));
        } catch (IOException e) {
            log.warn("Could not mark {} as a git safe.directory in container {}", workspaceFolder, containerId, e);
        }
    }

    private void inContainer(ExecTarget target, String containerId, String command)
            throws IOException, InterruptedException {
        executeSsh(target.runner(),
            ShellQuote.join(ContainerExec.of(target.runtime().cli(), containerId).shell(command).argv()));
    }

    private String executeSsh(CommandRunner runner, String command)
            throws IOException, InterruptedException {
        return runner.execute(command);
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
