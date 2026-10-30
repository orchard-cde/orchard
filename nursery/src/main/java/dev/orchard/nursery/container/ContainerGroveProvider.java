package dev.orchard.nursery.container;

import dev.orchard.core.model.ContainerPlot;
import dev.orchard.core.model.ContainerRuntime;
import dev.orchard.core.model.ContainerSpec;
import dev.orchard.core.model.Plot;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.ProviderType;
import dev.orchard.core.model.SeedlingSpec;
import dev.orchard.nursery.AbstractGroveProvider;
import dev.orchard.nursery.FruitGrower;
import dev.orchard.vine.CommandRunner;
import dev.orchard.vine.ContainerVine;
import dev.orchard.vine.ShellQuote;
import dev.orchard.vine.Vine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;

/**
 * Grove provider whose substrate is the local machine: a bind-mounted workspace directory and the
 * local docker or podman engine. Plots are reached through a local {@link CommandRunner}.
 */
public class ContainerGroveProvider extends AbstractGroveProvider<ContainerPlot, ContainerRuntime>
        implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ContainerGroveProvider.class);

    private static final long PROBE_TIMEOUT_SECONDS = 30;
    private static final long TEARDOWN_TIMEOUT_SECONDS = 120;

    private final ContainerConfig config;
    private final CommandRunner local;

    public ContainerGroveProvider(ContainerConfig config, FruitGrower fruitGrower, CommandRunner local) {
        super(ContainerPlot.class, Executors.newVirtualThreadPerTaskExecutor(), fruitGrower);
        this.config = config;
        this.local = local;
    }

    @Override
    public ProviderType type() {
        return ProviderType.CONTAINER;
    }

    @Override
    public boolean isAvailable() {
        try {
            return resolveRuntime().isPresent();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    protected ContainerPlot draft(UUID groveId, SeedlingSpec spec) {
        UUID id = UUID.randomUUID();
        return new ContainerPlot(id, groveId, getProviderId(), null, PlotState.GERMINATING,
            Instant.now(), null, plotRoot(id).resolve(id.toString()), null,
            new ContainerSpec(spec.cpuCores(), spec.memoryMb()), null);
    }

    @Override
    protected ContainerRuntime launch(ContainerPlot plot) throws Exception {
        ContainerRuntime runtime = resolveRuntime().orElseThrow(() -> new IllegalStateException(
            "no container engine reachable: docker info and podman info both failed"));
        local.execute("mkdir -p " + ShellQuote.quote(plot.workspacePath().toString()));
        return runtime;
    }

    @Override
    protected ContainerPlot ready(ContainerPlot plot, ContainerRuntime runtime) {
        return new ContainerPlot(plot.id(), plot.groveId(), plot.providerId(), plot.id().toString(),
            plot.state(), plot.plantedAt(), plot.readyAt(), plot.workspacePath(), runtime, plot.spec(), plot.remoteUser());
    }

    @Override
    protected Vine vineFor(ContainerPlot plot) {
        return new ContainerVine(plot, local);
    }

    @Override
    protected CommandRunner hostFor(ContainerPlot plot) {
        return local;
    }

    @Override
    protected ContainerRuntime runtime(ContainerPlot plot) {
        return plot.runtime();
    }

    @Override
    protected String workspacePathFor(ContainerPlot plot) {
        return plot.workspacePath().toString();
    }

    @Override
    public CompletableFuture<Plot> water(Plot plot) {
        ContainerPlot container = narrow(plot);
        requireDormant(container);
        return CompletableFuture.completedFuture(container.withState(PlotState.ROOTED));
    }

    @Override
    public CompletableFuture<Plot> dormant(Plot plot) {
        ContainerPlot container = narrow(plot);
        return CompletableFuture.completedFuture(container.withState(PlotState.DORMANT));
    }

    @Override
    public CompletableFuture<Plot> inspect(Plot plot) {
        ContainerPlot container = narrow(plot);
        return CompletableFuture.supplyAsync(() -> {
            try {
                String present = local.execute("if [ -d " + ShellQuote.quote(container.workspacePath().toString())
                    + " ]; then echo present; else echo missing; fi", PROBE_TIMEOUT_SECONDS).trim();
                if (!present.equals("present")) {
                    return container.withState(PlotState.WITHERED);
                }
                String running = local.execute(container.runtime().cli() + " ps -q --filter "
                    + labelFilter(container) + " --filter status=running", PROBE_TIMEOUT_SECONDS).trim();
                return container.withState(running.isEmpty() ? PlotState.DORMANT : PlotState.ROOTED);
            } catch (IOException e) {
                throw new CompletionException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> uproot(Plot plot) {
        ContainerPlot container = narrow(plot);
        return CompletableFuture.runAsync(() -> {
            log.info("Uprooting plot {}", container.id());
            Path plotDir = checkedPlotDir(container);
            try {
                if (container.runtime() != null) {
                    String cli = container.runtime().cli();
                    String ids = local.execute(cli + " ps -aq --filter " + labelFilter(container),
                        TEARDOWN_TIMEOUT_SECONDS);
                    for (String id : ids.lines().map(String::trim).filter(s -> !s.isEmpty()).toList()) {
                        local.execute(cli + " rm -f " + ShellQuote.quote(id), TEARDOWN_TIMEOUT_SECONDS);
                    }
                }
                local.execute("rm -rf " + ShellQuote.quote(plotDir.toString()),
                    TEARDOWN_TIMEOUT_SECONDS);
            } catch (IOException e) {
                throw new CompletionException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            }
        }, executor);
    }

    @Override
    public void close() {
        executor.shutdown();
    }

    /**
     * Prefers the podman CLI whenever a Podman engine is reachable, even through a {@code docker}
     * CLI pointed at it: the Docker-compat API can pull the wrong architecture variant.
     */
    private Optional<ContainerRuntime> resolveRuntime() throws InterruptedException {
        boolean dockerUp = succeeds("docker info");
        if (dockerUp && !dockerFrontsPodman()) {
            return Optional.of(ContainerRuntime.DOCKER);
        }
        if (succeeds("podman info")) {
            return Optional.of(ContainerRuntime.PODMAN);
        }
        return dockerUp ? Optional.of(ContainerRuntime.DOCKER) : Optional.empty();
    }

    private boolean dockerFrontsPodman() throws InterruptedException {
        try {
            return local.execute("docker version --format '{{json .Server.Components}}'", PROBE_TIMEOUT_SECONDS)
                .contains("Podman Engine");
        } catch (IOException e) {
            return false;
        }
    }

    private boolean succeeds(String command) throws InterruptedException {
        try {
            local.execute(command, PROBE_TIMEOUT_SECONDS);
            return true;
        } catch (IOException e) {
            log.debug("{} failed: {}", command, e.getMessage());
            return false;
        }
    }

    /**
     * The workspace is named after the plot, not a fixed {@code workspace}, because the devcontainer
     * CLI names a Compose project after the workspace folder's basename.
     */
    private static Path checkedPlotDir(ContainerPlot plot) {
        Path workspace = plot.workspacePath();
        Path dir = workspace.getParent();
        String id = plot.id().toString();
        boolean valid = workspace.isAbsolute()
            && workspace.getFileName() != null && workspace.getFileName().toString().equals(id)
            && dir != null && dir.getFileName() != null && dir.getFileName().toString().equals(id)
            && dir.getParent() != null;
        if (!valid) {
            throw new IllegalStateException("refusing to delete unexpected plot directory: " + workspace);
        }
        return dir;
    }

    private Path plotRoot(UUID id) {
        return config.workspaceRoot().resolve(id.toString());
    }

    private static String labelFilter(ContainerPlot plot) {
        return ShellQuote.quote("label=orchard.plot.id=" + plot.id());
    }
}
