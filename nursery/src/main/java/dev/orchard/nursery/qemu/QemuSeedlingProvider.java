package dev.orchard.nursery.qemu;

import dev.orchard.core.model.Seedling;
import dev.orchard.core.model.SeedlingState;
import dev.orchard.nursery.SeedlingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * QEMU-based seedling provider for local VM provisioning.
 * Uses QEMU/KVM to run VMs with cloud-init for initial configuration.
 */
public class QemuSeedlingProvider implements SeedlingProvider {

    private static final Logger log = LoggerFactory.getLogger(QemuSeedlingProvider.class);
    private static final String PROVIDER_ID = "qemu-local";

    private final QemuConfig config;
    private final ExecutorService executor;
    private final ConcurrentHashMap<UUID, Process> runningVms;
    private final AtomicInteger nextSshPort;

    public QemuSeedlingProvider(QemuConfig config) {
        this.config = config;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.runningVms = new ConcurrentHashMap<>();
        this.nextSshPort = new AtomicInteger(config.sshPortRangeStart());
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public CompletableFuture<Seedling> plant(Seedling seedling) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("Planting seedling {} with spec: {}", seedling.id(), seedling.spec());

                // Create VM directory
                Path vmDir = config.vmStoragePath().resolve(seedling.id().toString());
                Files.createDirectories(vmDir);

                // Create disk image (copy-on-write from base)
                Path diskImage = vmDir.resolve("disk.qcow2");
                createDiskImage(diskImage, seedling.spec().diskGb());

                // Generate cloud-init ISO
                Path cloudInitIso = vmDir.resolve("cloud-init.iso");
                createCloudInitIso(cloudInitIso, seedling);

                // Allocate SSH port
                int sshPort = allocateSshPort();

                // Build and start QEMU process
                Process vmProcess = startQemuProcess(seedling, diskImage, cloudInitIso, sshPort);
                runningVms.put(seedling.id(), vmProcess);

                // Update seedling with provider details
                Seedling sprouting = seedling
                    .withProviderDetails(seedling.id().toString(), "127.0.0.1")
                    .withState(SeedlingState.SPROUTING);

                // Wait for VM to be ready (SSH accessible)
                waitForSsh("127.0.0.1", sshPort);

                return new Seedling(
                    sprouting.id(),
                    sprouting.groveId(),
                    sprouting.providerInstanceId(),
                    "127.0.0.1",
                    sshPort,
                    SeedlingState.SAPLING,
                    sprouting.spec(),
                    sprouting.plantedAt(),
                    java.time.Instant.now()
                );

            } catch (Exception e) {
                log.error("Failed to plant seedling {}", seedling.id(), e);
                return seedling.withState(SeedlingState.BLIGHTED);
            }
        }, executor);
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
            Process process = runningVms.get(seedling.id());
            if (process != null) {
                // Send QEMU monitor command to pause
                // TODO: Implement QEMU monitor integration
            }
            return seedling.withState(SeedlingState.WILTING);
        }, executor);
    }

    @Override
    public CompletableFuture<Void> uproot(Seedling seedling) {
        return CompletableFuture.runAsync(() -> {
            log.info("Uprooting seedling {}", seedling.id());
            Process process = runningVms.remove(seedling.id());
            if (process != null) {
                process.destroyForcibly();
            }

            // Clean up VM directory
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
            Process process = runningVms.get(seedling.id());
            if (process != null && process.isAlive()) {
                return seedling.withState(SeedlingState.SAPLING);
            }
            return seedling.withState(SeedlingState.WITHERED);
        }, executor);
    }

    @Override
    public boolean isAvailable() {
        return Files.isExecutable(config.qemuBinary()) &&
               Files.isExecutable(config.qemuImgBinary());
    }

    private void createDiskImage(Path diskImage, int sizeGb) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
            config.qemuImgBinary().toString(),
            "create",
            "-f", "qcow2",
            "-b", config.baseImagePath().toString(),
            "-F", "qcow2",
            diskImage.toString(),
            sizeGb + "G"
        );
        pb.inheritIO();
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Failed to create disk image, exit code: " + exitCode);
        }
    }

    private void createCloudInitIso(Path isoPath, Seedling seedling) throws IOException, InterruptedException {
        Path tempDir = Files.createTempDirectory("cloud-init-");
        try {
            // Create meta-data
            Files.writeString(tempDir.resolve("meta-data"),
                "instance-id: " + seedling.id() + "\n" +
                "local-hostname: orchard-" + seedling.id().toString().substring(0, 8) + "\n"
            );

            // Create user-data
            Files.writeString(tempDir.resolve("user-data"),
                "#cloud-config\n" +
                "users:\n" +
                "  - name: cultivator\n" +
                "    sudo: ALL=(ALL) NOPASSWD:ALL\n" +
                "    shell: /bin/bash\n" +
                "    ssh_authorized_keys:\n" +
                "      - ${ORCHARD_SSH_PUBLIC_KEY}\n" +
                "packages:\n" +
                "  - docker.io\n" +
                "  - git\n" +
                "runcmd:\n" +
                "  - systemctl enable docker\n" +
                "  - systemctl start docker\n" +
                "  - usermod -aG docker cultivator\n"
            );

            // Generate ISO
            ProcessBuilder pb = new ProcessBuilder(
                "genisoimage",
                "-output", isoPath.toString(),
                "-volid", "cidata",
                "-joliet",
                "-rock",
                tempDir.resolve("meta-data").toString(),
                tempDir.resolve("user-data").toString()
            );
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("Failed to create cloud-init ISO, exit code: " + exitCode);
            }
        } finally {
            // Cleanup temp directory
            Files.walk(tempDir)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        log.warn("Failed to delete temp file {}", path, e);
                    }
                });
        }
    }

    private Process startQemuProcess(Seedling seedling, Path diskImage, Path cloudInitIso, int sshPort)
            throws IOException {
        var spec = seedling.spec();

        ProcessBuilder pb = new ProcessBuilder(
            config.qemuBinary().toString(),
            "-name", "orchard-" + seedling.id().toString().substring(0, 8),
            "-m", spec.memoryMb() + "M",
            "-smp", String.valueOf(spec.cpuCores()),
            "-drive", "file=" + diskImage + ",format=qcow2",
            "-cdrom", cloudInitIso.toString(),
            "-netdev", "user,id=net0,hostfwd=tcp::" + sshPort + "-:22",
            "-device", "virtio-net-pci,netdev=net0",
            "-nographic",
            "-serial", "mon:stdio"
        );

        if (config.enableKvm()) {
            pb.command().add("-enable-kvm");
        }

        log.info("Starting QEMU: {}", String.join(" ", pb.command()));
        return pb.start();
    }

    private int allocateSshPort() {
        int port = nextSshPort.getAndIncrement();
        if (port > config.sshPortRangeEnd()) {
            nextSshPort.set(config.sshPortRangeStart());
            port = config.sshPortRangeStart();
        }
        return port;
    }

    private void waitForSsh(String host, int port) {
        log.info("Waiting for SSH to be available at {}:{}", host, port);
        int maxAttempts = 60;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                java.net.Socket socket = new java.net.Socket();
                socket.connect(new java.net.InetSocketAddress(host, port), 1000);
                socket.close();
                log.info("SSH available at {}:{}", host, port);
                return;
            } catch (IOException e) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.warn("Timeout waiting for SSH at {}:{}", host, port);
    }

    public void shutdown() {
        log.info("Shutting down QEMU provider, stopping {} VMs", runningVms.size());
        runningVms.values().forEach(Process::destroyForcibly);
        runningVms.clear();
        executor.shutdown();
    }
}
