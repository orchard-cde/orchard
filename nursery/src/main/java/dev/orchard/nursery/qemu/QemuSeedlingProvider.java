package dev.orchard.nursery.qemu;

import dev.orchard.core.model.Seedling;
import dev.orchard.core.model.SeedlingState;
import dev.orchard.nursery.AbstractSeedlingProvider;
import dev.orchard.nursery.DevcontainerCliConfig;
import dev.orchard.nursery.PlantedSeedling;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * QEMU-based seedling provider for local VM provisioning.
 * Uses QEMU/KVM to run VMs with cloud-init for initial configuration.
 */
public class QemuSeedlingProvider extends AbstractSeedlingProvider<QemuSeedlingProvider.QemuLaunch> {

    private static final Logger log = LoggerFactory.getLogger(QemuSeedlingProvider.class);
    private static final String PROVIDER_ID = "qemu-local";

    private final QemuConfig config;
    private final DevcontainerCliConfig devcontainerCliConfig;
    private final ConcurrentHashMap<UUID, ProcessHandle> runningVms;
    private final QemuCommands commands;

    /**
     * Launch state QEMU must carry from {@code launch} to the later planting steps: the SSH port is
     * chosen before the process starts (it is a QEMU argument) so it cannot be rediscovered later.
     */
    public record QemuLaunch(int sshPort) {}

    public QemuSeedlingProvider(QemuConfig config, DevcontainerCliConfig devcontainerCliConfig) {
        this(config, devcontainerCliConfig, new DefaultQemuCommands(config, devcontainerCliConfig));
    }

    /** Test seam. Production callers use the two-arg constructor. */
    QemuSeedlingProvider(QemuConfig config, DevcontainerCliConfig devcontainerCliConfig, QemuCommands commands) {
        super(Executors.newVirtualThreadPerTaskExecutor());
        this.config = config;
        this.devcontainerCliConfig = devcontainerCliConfig;
        this.runningVms = new ConcurrentHashMap<>();
        this.commands = commands;
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    protected QemuLaunch launch(Seedling seedling) throws IOException, InterruptedException {
        log.info("Planting seedling {} with spec: {}", seedling.id(), seedling.spec());

        Path vmDir = config.vmStoragePath().resolve(seedling.id().toString());
        Files.createDirectories(vmDir);

        Path diskImage = vmDir.resolve("disk.qcow2");
        commands.createDiskImage(diskImage, seedling.spec().diskGb());

        Path cloudInitIso = vmDir.resolve("cloud-init.iso");
        commands.createCloudInitIso(cloudInitIso, seedling);

        int sshPort = commands.allocateSshPort();

        Process vmProcess = commands.startQemu(seedling, diskImage, cloudInitIso, sshPort);
        Files.writeString(vmDir.resolve("qemu.pid"), String.valueOf(vmProcess.pid()));
        runningVms.put(seedling.id(), vmProcess.toHandle());

        return new QemuLaunch(sshPort);
    }

    @Override
    protected PlantedSeedling resolveEndpoint(Seedling seedling, QemuLaunch launched) {
        return new PlantedSeedling(seedling.id().toString(), "127.0.0.1", launched.sshPort());
    }

    @Override
    protected void awaitReachable(Seedling seedling, PlantedSeedling planted) throws IOException {
        waitForSsh(planted.host(), planted.sshPort());
    }

    @Override
    public CompletableFuture<Seedling> water(Seedling seedling) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Watering (resuming) seedling {}", seedling.id());
            // TODO: Implement resume from suspended state
            return seedling.withState(SeedlingState.SAPLING);
        }, executor);
    }

    @Override
    public CompletableFuture<Seedling> dormant(Seedling seedling) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Setting seedling {} to dormant", seedling.id());
            return seedling.withState(SeedlingState.WILTING);
        }, executor);
    }

    @Override
    public CompletableFuture<Void> uproot(Seedling seedling) {
        return CompletableFuture.runAsync(() -> {
            log.info("Uprooting seedling {}", seedling.id());
            ProcessHandle handle = runningVms.remove(seedling.id());
            if (handle != null) {
                handle.destroyForcibly();
            }

            // Clean up VM directory (PID file is inside, deleted with the rest)
            Path vmDir = config.vmStoragePath().resolve(seedling.id().toString());
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

    @Override
    public CompletableFuture<Seedling> inspect(Seedling seedling) {
        return CompletableFuture.supplyAsync(() -> {
            ProcessHandle handle = runningVms.get(seedling.id());
            if (handle != null && handle.isAlive()) {
                return seedling.withState(SeedlingState.SAPLING);
            }
            return seedling.withState(SeedlingState.WITHERED);
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

    private void waitForSsh(String host, int port) throws IOException {
        log.info("Waiting for SSH to be available at {}:{}", host, port);

        // Phase 1: Wait for the TCP port to open
        int maxPortAttempts = 60;
        for (int i = 0; i < maxPortAttempts; i++) {
            try {
                java.net.Socket socket = new java.net.Socket();
                socket.connect(new java.net.InetSocketAddress(host, port), 1000);
                socket.close();
                log.info("SSH port open at {}:{}, waiting for cloud-init to complete...", host, port);
                break;
            } catch (IOException e) {
                if (i == maxPortAttempts - 1) {
                    throw new IOException("Timeout waiting for SSH port at " + host + ":" + port);
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for SSH port at " + host + ":" + port, ie);
                }
            }
        }

        // Phase 2: Wait for actual SSH authentication to work (cloud-init must finish)
        java.nio.file.Path orchardKey = config.sshKeyPath();
        int maxAuthAttempts = 30;
        for (int i = 0; i < maxAuthAttempts; i++) {
            try {
                var cmd = new dev.orchard.vine.SshCommandBuilder()
                    .host(host)
                    .port(port)
                    .identityKey(orchardKey)
                    .connectTimeoutSeconds(5)
                    .batchMode(true)
                    .remoteCommand("echo ready")
                    .build();

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                int exitCode = p.waitFor();
                if (exitCode == 0) {
                    log.info("SSH authentication successful at {}:{}", host, port);
                    return;
                }
            } catch (Exception e) {
                // ignore, retry
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for SSH authentication at " + host + ":" + port, ie);
            }
        }
        throw new IOException("Timeout waiting for SSH authentication at " + host + ":" + port);
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
