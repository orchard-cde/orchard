package dev.orchard.nursery.qemu;

import dev.orchard.core.model.Plot;
import dev.orchard.core.model.ProviderType;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.VmEndpoint;
import dev.orchard.core.model.Host;
import dev.orchard.core.model.ProviderHandle;
import dev.orchard.core.model.VmPlot;
import dev.orchard.nursery.AbstractVmGroveProvider;
import dev.orchard.nursery.DevcontainerCliConfig;
import dev.orchard.nursery.FruitGrower;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * QEMU-based grove provider for local VM provisioning.
 * Uses QEMU/KVM to run VMs with cloud-init for initial configuration.
 */
public class QemuGroveProvider extends AbstractVmGroveProvider<QemuGroveProvider.QemuLaunch> {

    private static final Logger log = LoggerFactory.getLogger(QemuGroveProvider.class);

    /**
     * How long {@link #uproot} waits for {@code destroyForcibly()} before treating the VM as
     * leaked. A constant, not a {@link QemuConfig} property: SIGKILL is unblockable, so no longer
     * grace period is legitimate — a survivor means something is badly wrong (e.g. uninterruptible
     * I/O) and teardown must fail rather than be retried with a bigger number.
     */
    private static final Duration DESTROY_FORCIBLY_TIMEOUT = Duration.ofSeconds(5);

    private final QemuConfig config;
    private final ConcurrentHashMap<UUID, ProcessHandle> runningVms;
    private final QemuCommands commands;
    private final Duration poweroffTimeout;

    /**
     * Launch state QEMU must carry from {@code launch} to the later planting steps: the SSH port is
     * chosen before the process starts (it is a QEMU argument) so it cannot be rediscovered later.
     */
    public record QemuLaunch(int sshPort) {}

    public QemuGroveProvider(QemuConfig config, DevcontainerCliConfig devcontainerCliConfig,
                                FruitGrower fruitGrower) {
        this(config, devcontainerCliConfig, fruitGrower,
            new DefaultQemuCommands(config, devcontainerCliConfig), Duration.ofSeconds(60));
    }

    /** Test seam. Production callers use the three-arg constructor. */
    QemuGroveProvider(QemuConfig config, DevcontainerCliConfig devcontainerCliConfig,
                         FruitGrower fruitGrower, QemuCommands commands, Duration poweroffTimeout) {
        super(Executors.newVirtualThreadPerTaskExecutor(), fruitGrower);
        this.config = config;
        this.runningVms = new ConcurrentHashMap<>();
        this.commands = commands;
        this.poweroffTimeout = poweroffTimeout;
    }

    @Override
    public ProviderType type() {
        return ProviderType.QEMU_LOCAL;
    }

    @Override
    protected QemuLaunch launch(VmPlot plot) throws IOException, InterruptedException {
        log.info("Planting plot {} with spec: {}", plot.id(), plot.spec());

        Path vmDir = vmDir(plot.id());
        Files.createDirectories(vmDir);

        Path diskImage = vmDir.resolve("disk.qcow2");
        commands.createDiskImage(diskImage, plot.spec().diskGb());

        Path cloudInitIso = vmDir.resolve("cloud-init.iso");
        commands.createCloudInitIso(cloudInitIso, plot);

        int sshPort = commands.allocateSshPort();

        Process vmProcess = commands.startQemu(plot, diskImage, cloudInitIso, sshPort);
        Files.writeString(pidFile(plot.id()), String.valueOf(vmProcess.pid()));
        runningVms.put(plot.id(), vmProcess.toHandle());

        return new QemuLaunch(sshPort);
    }

    @Override
    protected VmEndpoint resolveEndpoint(VmPlot plot, QemuLaunch launched) {
        return new VmEndpoint(new ProviderHandle(plot.id().toString()), new Host("127.0.0.1"),
            launched.sshPort());
    }

    @Override
    protected void awaitReachable(VmPlot plot, VmEndpoint endpoint) throws IOException {
        commands.awaitReachable(endpoint.host().value(), endpoint.sshPort());
    }

    @Override
    public CompletableFuture<Plot> water(Plot plot) {
        VmPlot vm = narrow(plot);
        requireDormant(vm);
        ProcessHandle tracked = runningVms.get(vm.id());
        if (tracked != null && tracked.isAlive()) {
            throw new IllegalStateException(
                "Plot " + vm.id() + " already has a running VM (pid " + tracked.pid() + ")");
        }
        return CompletableFuture.supplyAsync(() -> {
            log.info("Watering plot {}", vm.id());
            Path vmDir = vmDir(vm.id());
            Process process = null;
            try {
                int sshPort = commands.allocateSshPort();
                process = commands.startQemu(vm, vmDir.resolve("disk.qcow2"),
                    vmDir.resolve("cloud-init.iso"), sshPort);
                Files.writeString(pidFile(vm.id()), String.valueOf(process.pid()));
                runningVms.put(vm.id(), process.toHandle());

                VmEndpoint endpoint = resolveEndpoint(vm, new QemuLaunch(sshPort));
                awaitReachable(vm, endpoint);
                return vm.withEndpoint(endpoint).withState(PlotState.ROOTED);
            } catch (IOException | RuntimeException e) {
                abandonRelaunch(vm, vmDir, process, e);
                throw new CompletionException(e);
            }
        }, executor);
    }

    /** A relaunched VM that cannot be confirmed dead stays tracked, so it is neither forgotten nor relaunched over. */
    private void abandonRelaunch(VmPlot plot, Path vmDir, Process process, Exception cause) {
        if (process == null) {
            return;
        }
        ProcessHandle handle = process.toHandle();
        try {
            confirmTerminated(plot, handle);
        } catch (CompletionException e) {
            cause.addSuppressed(e);
            return;
        }
        runningVms.remove(plot.id(), handle);
        try {
            Files.deleteIfExists(vmDir.resolve("qemu.pid"));
        } catch (IOException e) {
            log.warn("Failed to remove pid file for plot {}", plot.id(), e);
        }
    }

    @Override
    public CompletableFuture<Plot> dormant(Plot plot) {
        VmPlot vm = narrow(plot);
        return CompletableFuture.supplyAsync(() -> {
            log.info("Setting plot {} to dormant", vm.id());
            ProcessHandle handle = runningVms.get(vm.id());
            if (handle != null) {
                if (handle.isAlive()) {
                    commands.requestPoweroff(vm);
                    awaitPoweroff(vm, handle);
                }
                runningVms.remove(vm.id());
                Path pidFile = pidFile(vm.id());
                try {
                    Files.deleteIfExists(pidFile);
                } catch (IOException e) {
                    throw new CompletionException("Failed to remove " + pidFile, e);
                }
            }
            return vm.withState(PlotState.DORMANT);
        }, executor);
    }

    private void awaitPoweroff(VmPlot plot, ProcessHandle handle) {
        try {
            handle.onExit().get(poweroffTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(
                "Interrupted while waiting for QEMU process (pid " + handle.pid()
                    + ") for plot " + plot.id() + " to power off", e);
        } catch (ExecutionException | TimeoutException e) {
            log.warn("Plot {} did not power off within {}; forcing termination", plot.id(), poweroffTimeout);
            confirmTerminated(plot, handle);
        }
    }

    @Override
    public CompletableFuture<Void> uproot(Plot plot) {
        VmPlot vm = narrow(plot);
        return CompletableFuture.runAsync(() -> {
            log.info("Uprooting plot {}", vm.id());
            ProcessHandle handle = runningVms.remove(vm.id());
            if (handle != null) {
                confirmTerminated(vm, handle);
            }

            // Clean up VM directory (PID file is inside, deleted with the rest)
            Path vmDir = vmDir(vm.id());
            try {
                if (Files.exists(vmDir)) {
                    Files.walk(vmDir)
                        .sorted((a, b) -> b.compareTo(a))
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                log.warn("Failed to delete {}", path, e);
                            }
                        });
                }
            } catch (IOException e) {
                log.warn("Failed to clean up VM directory {}", vmDir, e);
            }
        }, executor);
    }

    /**
     * Kills {@code handle} and blocks until the OS confirms it is gone, so {@link #uproot} cannot
     * report success while the VM still runs. The handle is usually not this JVM's child (QEMU is
     * launched via {@code setsid}, and {@link #reattachSurvivingVms} recovers handles with
     * {@code ProcessHandle.of(pid)}); {@link ProcessHandle#onExit()} handles that by polling.
     *
     * @throws CompletionException if the process has not exited within {@link #DESTROY_FORCIBLY_TIMEOUT}
     */
    private void confirmTerminated(VmPlot plot, ProcessHandle handle) {
        handle.destroyForcibly();
        try {
            handle.onExit().get(DESTROY_FORCIBLY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(
                "Interrupted while waiting for QEMU process (pid " + handle.pid()
                    + ") for plot " + plot.id() + " to exit after destroyForcibly()", e);
        } catch (ExecutionException e) {
            throw new CompletionException(
                "Failed while waiting for QEMU process (pid " + handle.pid()
                    + ") for plot " + plot.id() + " to exit after destroyForcibly()",
                e.getCause() != null ? e.getCause() : e);
        } catch (TimeoutException e) {
            throw new CompletionException(
                "QEMU process (pid " + handle.pid() + ") for plot " + plot.id()
                    + " did not exit within " + DESTROY_FORCIBLY_TIMEOUT
                    + " of destroyForcibly() — treating substrate as not released", e);
        }
        if (handle.isAlive()) {
            String message = "QEMU process (pid " + handle.pid() + ") for plot " + plot.id()
                + " is still alive after destroyForcibly() reported exit — treating substrate as not released";
            throw new CompletionException(message, new IllegalStateException(message));
        }
    }

    @Override
    public CompletableFuture<Plot> inspect(Plot plot) {
        VmPlot vm = narrow(plot);
        return CompletableFuture.supplyAsync(() -> {
            ProcessHandle handle = runningVms.get(vm.id());
            if (handle != null && handle.isAlive()) {
                return vm.withState(PlotState.ROOTED);
            }
            Path vmDir = vmDir(vm.id());
            if (Files.exists(vmDir.resolve("disk.qcow2"))) {
                if (Files.exists(pidFile(vm.id()))) {
                    log.warn("Plot {} has a stale qemu.pid and no running VM; it was not shut down cleanly",
                        vm.id());
                }
                return vm.withState(PlotState.DORMANT);
            }
            return vm.withState(PlotState.WITHERED);
        }, executor);
    }

    @Override
    public boolean isAvailable() {
        boolean qemuOk = Files.isExecutable(config.qemuBinary());
        boolean qemuImgOk = Files.isExecutable(config.qemuImgBinary());
        boolean baseImageOk = Files.exists(config.baseImagePath());

        if (!qemuOk || !qemuImgOk || !baseImageOk) {
            log.debug("QEMU provider not available: qemu={}, qemu-img={}, base-image={}",
                qemuOk, qemuImgOk, baseImageOk);
        }

        return qemuOk && qemuImgOk && baseImageOk;
    }

    /**
     * Builds the cloud-init {@code ssh_authorized_keys} block. Combines the trellis/server
     * shared key (kept as-is) with the cultivator's registered public keys so both the server
     * automation and the cultivator can SSH into the seedling.
     */
    static String buildSshAuthorizedKeysBlock(String configuredKey, List<String> registeredKeys) {
        List<String> keys = new ArrayList<>();
        if (configuredKey != null && !configuredKey.isBlank()) {
            keys.add(configuredKey);
        }
        if (registeredKeys != null) {
            registeredKeys.stream()
                .filter(key -> key != null && !key.isBlank())
                .forEach(keys::add);
        }
        if (keys.isEmpty()) {
            return "";
        }
        StringBuilder block = new StringBuilder("    ssh_authorized_keys:\n");
        for (String key : keys) {
            block.append("      - ").append(key).append('\n');
        }
        return block.toString();
    }

    private Path vmDir(UUID id) {
        return config.vmStoragePath().resolve(id.toString());
    }

    private Path pidFile(UUID id) {
        return vmDir(id).resolve("qemu.pid");
    }

    public void reattachSurvivingVms() {
        Path storageDir = config.vmStoragePath();
        if (!Files.exists(storageDir)) {
            return;
        }
        try (var dirs = Files.list(storageDir)) {
            dirs.filter(Files::isDirectory).forEach(vmDir -> {
                Path pidFile = vmDir.resolve("qemu.pid");
                if (!Files.exists(pidFile)) {
                    return;
                }
                try {
                    long pid = Long.parseLong(Files.readString(pidFile).trim());
                    UUID seedlingId = UUID.fromString(vmDir.getFileName().toString());
                    ProcessHandle.of(pid).ifPresentOrElse(
                        handle -> {
                            if (!handle.isAlive()) {
                                log.info("QEMU VM (PID {}) for seedling {} exited — will be reconciled", pid, seedlingId);
                            } else if (isOurQemuVm(handle, seedlingId)) {
                                runningVms.put(seedlingId, handle);
                                log.info("Re-attached to surviving QEMU VM (PID {}) for seedling {}", pid, seedlingId);
                            } else {
                                // PID reuse: the recorded PID is alive but now belongs to an unrelated
                                // process. Adopting it would let inspect()/uproot() act on — and
                                // destroyForcibly() — a process that isn't ours, so we refuse.
                                log.warn("PID {} for seedling {} is alive but is not its QEMU VM "
                                    + "(PID reused since {}) — not adopting", pid, seedlingId, pidFile);
                            }
                        },
                        () -> log.info("No process found with PID {} for seedling {} — VM did not survive restart", pid, seedlingId)
                    );
                } catch (IOException | IllegalArgumentException e) {
                    log.warn("Failed to read PID file {}", pidFile, e);
                }
            });
        } catch (IOException e) {
            log.warn("Failed to scan VM storage path {} for surviving VMs", storageDir, e);
        }
    }

    /**
     * Positively identifies {@code handle} as this seedling's QEMU VM before we adopt it on
     * reattach. PIDs are recycled: a PID recorded in {@code qemu.pid} may, after a reboot or PID
     * wraparound, belong to a completely unrelated process. Without this check that process would
     * be tracked in {@link #runningVms}, reported alive by {@link #inspect}, and — most dangerously
     * — force-killed by {@link #uproot}.
     *
     * <p>The VM's disk and cloud-init paths embed the full seedling id, so a readable command line
     * binds the PID to <em>this</em> seedling unambiguously. When the full argv is unavailable
     * (e.g. truncated), we fall back to requiring the executable itself to be a QEMU system
     * emulator, which still prevents adopting (and later killing) a non-QEMU process.
     */
    boolean isOurQemuVm(ProcessHandle handle, UUID seedlingId) {
        ProcessHandle.Info info = handle.info();
        if (info.commandLine().map(cmd -> cmd.contains(seedlingId.toString())).orElse(false)) {
            return true;
        }
        return info.command().map(cmd -> cmd.contains("qemu-system")).orElse(false);
    }

    public void shutdown() {
        log.info("QEMU provider shutting down — leaving {} VM(s) running for reattachment on next startup", runningVms.size());
        runningVms.clear();
        executor.shutdown();
    }
}
