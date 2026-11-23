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
import dev.orchard.vine.ContainerCommandRunner;
import dev.orchard.vine.ContainerLabels;
import dev.orchard.vine.ContainerVine;
import dev.orchard.vine.ShellQuote;
import dev.orchard.vine.Vine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;

/**
 * Grove provider whose substrate is the local machine: a bind-mounted workspace directory and the
 * local docker or podman engine. Plots are reached through a {@link ContainerCommandRunner}.
 */
public class ContainerGroveProvider extends AbstractGroveProvider<ContainerPlot, ContainerRuntime>
        implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ContainerGroveProvider.class);

    private static final long PROBE_TIMEOUT_SECONDS = 30;
    private static final long TEARDOWN_TIMEOUT_SECONDS = 120;

    private final ContainerConfig config;
    private final ContainerCommandRunner engine;

    public ContainerGroveProvider(ContainerConfig config, FruitGrower fruitGrower, ContainerCommandRunner engine) {
        super(ContainerPlot.class, Executors.newVirtualThreadPerTaskExecutor(), fruitGrower);
        this.config = config;
        this.engine = engine;
    }

    @Override
    public ProviderType type() {
        return ProviderType.CONTAINER;
    }

    @Override
    public boolean isAvailable() {
        try {
            return ContainerRuntimes.resolve(engine).isPresent();
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
        ContainerRuntime runtime = ContainerRuntimes.resolve(engine).orElseThrow(() -> new IllegalStateException(
            "no container engine reachable: docker info and podman info both failed"));
        engine.execute("mkdir -p " + ShellQuote.quote(plot.workspacePath().toString()));
        return runtime;
    }

    @Override
    protected ContainerPlot ready(ContainerPlot plot, ContainerRuntime runtime) {
        return new ContainerPlot(plot.id(), plot.groveId(), plot.providerId(), plot.id().toString(),
            plot.state(), plot.plantedAt(), plot.readyAt(), plot.workspacePath(), runtime, plot.spec(), plot.remoteUser());
    }

    @Override
    protected Vine vineFor(ContainerPlot plot) {
        return new ContainerVine(plot, engine);
    }

    @Override
    protected CommandRunner hostFor(ContainerPlot plot) {
        return engine;
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
        return CompletableFuture.completedFuture(container.withState(PlotState.ROOTED));
    }

    @Override
    public CompletableFuture<Plot> dormant(Plot plot) {
        ContainerPlot container = narrow(plot);
        return CompletableFuture.supplyAsync(() -> {
            try {
                String cli = container.runtime().cli();
                List<String> running = plotContainerIds(cli, container.id(), false, TEARDOWN_TIMEOUT_SECONDS);
                if (!running.isEmpty()) {
                    engine.execute(cli + " stop " + ShellQuote.join(running), TEARDOWN_TIMEOUT_SECONDS);
                }
                List<String> stillRunning = plotContainerIds(cli, container.id(), false, PROBE_TIMEOUT_SECONDS);
                if (!stillRunning.isEmpty()) {
                    throw new IllegalStateException("plot " + container.id() + " still has running containers: "
                        + stillRunning);
                }
                return container.withState(PlotState.DORMANT);
            } catch (IOException e) {
                throw new CompletionException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Plot> inspect(Plot plot) {
        ContainerPlot container = narrow(plot);
        return CompletableFuture.supplyAsync(() -> {
            try {
                String present = engine.execute("if [ -d " + ShellQuote.quote(container.workspacePath().toString())
                    + " ]; then echo present; else echo missing; fi", PROBE_TIMEOUT_SECONDS).trim();
                if (!present.equals("present")) {
                    return container.withState(PlotState.WITHERED);
                }
                List<String> running = plotContainerIds(container.runtime().cli(), container.id(), false,
                    PROBE_TIMEOUT_SECONDS);
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
                String cli = null;
                String image = null;
                if (container.runtime() != null) {
                    cli = container.runtime().cli();
                    List<String> primaries = ContainerLabels.containerIds(engine, cli, container.id(), true,
                        TEARDOWN_TIMEOUT_SECONDS);
                    image = primaries.isEmpty() ? null : imageOf(cli, primaries.getFirst());
                    Set<String> projects = composeProjects(cli, primaries, TEARDOWN_TIMEOUT_SECONDS);
                    for (String id : primaries) {
                        engine.execute(cli + " rm -f " + ShellQuote.quote(id), TEARDOWN_TIMEOUT_SECONDS);
                    }
                    for (String project : projects) {
                        ContainerLabels.removeComposeProject(engine, cli, project, TEARDOWN_TIMEOUT_SECONDS);
                    }
                }
                removePlotDir(plotDir, cli, image);
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

    /**
     * The plot's containers: its labelled primaries and every container of their compose projects,
     * which the primaries name in their labels whether or not they are running.
     */
    private List<String> plotContainerIds(String cli, UUID plotId, boolean includeStopped, long timeoutSeconds)
            throws IOException, InterruptedException {
        Set<String> ids = new LinkedHashSet<>(
            ContainerLabels.containerIds(engine, cli, plotId, includeStopped, timeoutSeconds));
        List<String> primaries = ContainerLabels.containerIds(engine, cli, plotId, true, timeoutSeconds);
        for (String project : composeProjects(cli, primaries, timeoutSeconds)) {
            ids.addAll(ContainerLabels.composeContainerIds(engine, cli, project, includeStopped, timeoutSeconds));
        }
        return List.copyOf(ids);
    }

    private Set<String> composeProjects(String cli, List<String> primaries, long timeoutSeconds)
            throws IOException, InterruptedException {
        Set<String> projects = new LinkedHashSet<>();
        for (String id : primaries) {
            ContainerLabels.composeProject(engine, cli, id, timeoutSeconds).ifPresent(projects::add);
        }
        return projects;
    }

    private String imageOf(String cli, String containerId) throws InterruptedException {
        try {
            String image = engine.execute(cli + " inspect --format " + ShellQuote.quote("{{.Config.Image}}") + " "
                + ShellQuote.quote(containerId), PROBE_TIMEOUT_SECONDS).trim();
            return image.isEmpty() ? null : image;
        } catch (IOException e) {
            log.warn("Could not read the image of container {}", containerId, e);
            return null;
        }
    }

    /**
     * Removes the plot directory. Files the fruit wrote as container root are owned by host root
     * under a rootful engine, so when the host {@code rm} fails the directory's contents are cleared
     * as root by a throwaway container from the fruit's image, and the host {@code rm} retried.
     */
    private void removePlotDir(Path plotDir, String cli, String image) throws IOException, InterruptedException {
        String rm = "rm -rf " + ShellQuote.quote(plotDir.toString());
        try {
            engine.execute(rm, TEARDOWN_TIMEOUT_SECONDS);
            return;
        } catch (IOException e) {
            if (image == null) {
                throw removeByHand(plotDir, e);
            }
            log.warn("Removing {} failed; clearing it as root from image {}", plotDir, image, e);
        }
        try {
            engine.execute(cli + " run --rm --pull never --network none -u 0 -v "
                + ShellQuote.quote(plotDir + ":/plot") + " --entrypoint /bin/sh " + ShellQuote.quote(image)
                + " -c " + ShellQuote.quote("rm -rf /plot/..?* /plot/.[!.]* /plot/*"), TEARDOWN_TIMEOUT_SECONDS);
            engine.execute(rm, TEARDOWN_TIMEOUT_SECONDS);
        } catch (IOException e) {
            throw removeByHand(plotDir, e);
        }
    }

    private static IOException removeByHand(Path plotDir, IOException cause) {
        return new IOException("could not remove plot directory " + plotDir + "; it needs removing by hand", cause);
    }

    private Path plotRoot(UUID id) {
        return config.workspaceRoot().resolve(id.toString());
    }
}
