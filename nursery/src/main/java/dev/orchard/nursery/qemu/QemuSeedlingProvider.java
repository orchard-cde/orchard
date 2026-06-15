package dev.orchard.nursery.qemu;

import dev.orchard.core.model.Seedling;
import dev.orchard.core.model.SeedlingState;
import dev.orchard.nursery.DevcontainerCliConfig;
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

/**
 * QEMU-based seedling provider for local VM provisioning.
 * Uses QEMU/KVM to run VMs with cloud-init for initial configuration.
 */
public class QemuSeedlingProvider implements SeedlingProvider {

    private static final Logger log = LoggerFactory.getLogger(QemuSeedlingProvider.class);
    private static final String PROVIDER_ID = "qemu-local";

    private final QemuConfig config;
    private final DevcontainerCliConfig devcontainerCliConfig;
    private final ExecutorService executor;
    private final ConcurrentHashMap<UUID, Process> runningVms;

    public QemuSeedlingProvider(QemuConfig config, DevcontainerCliConfig devcontainerCliConfig) {
        this.config = config;
        this.devcontainerCliConfig = devcontainerCliConfig;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.runningVms = new ConcurrentHashMap<>();
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

                // Provisioning preflight: a SAPLING with no devcontainer CLI would silently
                // fail every grow() call, so verify cloud-init actually installed the pinned
                // version before we declare the seedling READY. Failure → BLIGHTED (matches
                // the rest of the catch block, instance kept alive for operator forensics).
                Seedling sapling = new Seedling(
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
                verifyDevcontainerCli(sapling, devcontainerCliConfig.version());
                return sapling;

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
        boolean qemuOk = Files.isExecutable(config.qemuBinary());
        boolean qemuImgOk = Files.isExecutable(config.qemuImgBinary());
        boolean baseImageOk = Files.exists(config.baseImagePath());

        if (!qemuOk || !qemuImgOk || !baseImageOk) {
            log.debug("QEMU provider not available: qemu={}, qemu-img={}, base-image={}",
                qemuOk, qemuImgOk, baseImageOk);
        }

        return qemuOk && qemuImgOk && baseImageOk;
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

            // Resolve SSH public key: config property, then fallback to well-known file
            String sshPubKey = config.sshPublicKey();
            if (sshPubKey == null || sshPubKey.isBlank()) {
                Path defaultKeyPath = Path.of(config.sshKeyPath() + ".pub");
                if (Files.exists(defaultKeyPath)) {
                    sshPubKey = Files.readString(defaultKeyPath).trim();
                    log.info("Using SSH public key from {}", defaultKeyPath);
                }
            }

            // Build user-data with SSH public key
            StringBuilder userData = new StringBuilder();
            userData.append("#cloud-config\n");
            userData.append("users:\n");
            userData.append("  - name: cultivator\n");
            userData.append("    sudo: ALL=(ALL) NOPASSWD:ALL\n");
            userData.append("    shell: /bin/bash\n");
            if (sshPubKey != null && !sshPubKey.isBlank()) {
                userData.append("    ssh_authorized_keys:\n");
                userData.append("      - ").append(sshPubKey).append("\n");
            } else {
                log.warn("No SSH public key configured - VM will not be accessible via SSH key auth. " +
                    "Set orchard.qemu.ssh-public-key or place key at {}.pub", config.sshKeyPath());
            }
            userData.append("packages:\n");
            userData.append("  - docker.io\n");
            userData.append("  - git\n");
            userData.append("  - curl\n");
            userData.append("runcmd:\n");
            userData.append("  - curl -fsSL https://deb.nodesource.com/setup_20.x | bash -\n");
            userData.append("  - apt-get install -y nodejs\n");
            userData.append("  - npm install -g @devcontainers/cli@").append(devcontainerCliConfig.version()).append("\n");
            userData.append("  - systemctl enable docker\n");
            userData.append("  - systemctl start docker\n");
            userData.append("  - usermod -aG docker cultivator\n");
            userData.append("  - mkdir -p /workspace\n");
            userData.append("  - chown cultivator:cultivator /workspace\n");

            Files.writeString(tempDir.resolve("user-data"), userData.toString());

            // Generate ISO - try genisoimage first, then mkisofs (macOS via cdrtools)
            if (!tryGenerateIso(isoPath, tempDir, "genisoimage") &&
                !tryGenerateIso(isoPath, tempDir, "mkisofs")) {
                throw new IOException(
                    "Failed to create cloud-init ISO: neither genisoimage nor mkisofs found. " +
                    "Install via: apt install genisoimage (Linux) or brew install cdrtools (macOS)");
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

    private boolean tryGenerateIso(Path isoPath, Path tempDir, String isoBinary) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                isoBinary,
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
            if (exitCode == 0) {
                log.debug("Generated cloud-init ISO using {}", isoBinary);
                return true;
            }
        } catch (IOException e) {
            log.debug("{} not available: {}", isoBinary, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    private boolean isAarch64() {
        return config.qemuBinary().toString().contains("aarch64");
    }

    private boolean isKvmAccessible() {
        var kvm = java.nio.file.Paths.get("/dev/kvm");
        return Files.exists(kvm) && Files.isReadable(kvm) && Files.isWritable(kvm);
    }

    private Process startQemuProcess(Seedling seedling, Path diskImage, Path cloudInitIso, int sshPort)
            throws IOException {
        var spec = seedling.spec();
        var cmd = new java.util.ArrayList<String>();
        cmd.add(config.qemuBinary().toString());
        cmd.add("-name"); cmd.add("orchard-" + seedling.id().toString().substring(0, 8));
        cmd.add("-m"); cmd.add(spec.memoryMb() + "M");
        cmd.add("-smp"); cmd.add(String.valueOf(spec.cpuCores()));

        if (isAarch64()) {
            cmd.add("-machine"); cmd.add("virt");
            cmd.add("-cpu"); cmd.add("host");
            cmd.add("-accel"); cmd.add("hvf");
            // UEFI firmware required for aarch64
            Path efiCode = config.qemuBinary().getParent().getParent()
                .resolve("share/qemu/edk2-aarch64-code.fd");
            if (Files.exists(efiCode)) {
                cmd.add("-bios"); cmd.add(efiCode.toString());
            }
            cmd.add("-drive"); cmd.add("if=virtio,file=" + diskImage + ",format=qcow2");
            cmd.add("-drive"); cmd.add("file=" + cloudInitIso + ",format=raw,if=virtio");
        } else {
            cmd.add("-drive"); cmd.add("file=" + diskImage + ",format=qcow2");
            cmd.add("-cdrom"); cmd.add(cloudInitIso.toString());
        }

        cmd.add("-netdev"); cmd.add("user,id=net0,hostfwd=tcp::" + sshPort + "-:22");
        cmd.add("-device"); cmd.add("virtio-net-pci,netdev=net0");
        cmd.add("-nographic");

        Path vmDir = diskImage.getParent();
        String serialOutput = spec.serialOutput() != null ? spec.serialOutput() : config.serialOutput();
        if ("file".equalsIgnoreCase(serialOutput)) {
            cmd.add("-serial"); cmd.add("file:" + vmDir.resolve("serial.log"));
        } else {
            cmd.add("-serial"); cmd.add("mon:stdio");
        }

        if (config.enableKvm()) {
            if (isKvmAccessible()) {
                cmd.add("-enable-kvm");
            } else {
                log.warn("KVM enabled in config but /dev/kvm is not accessible — falling back to TCG (software emulation). " +
                    "Add user to the kvm group for hardware acceleration.");
            }
        }

        Path qemuLog = vmDir.resolve("qemu.log");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectOutput(qemuLog.toFile());
        pb.redirectErrorStream(true);
        log.info("Starting QEMU: {}", String.join(" ", pb.command()));
        return pb.start();
    }

    private int allocateSshPort() throws IOException {
        int start = config.sshPortRangeStart();
        int end = config.sshPortRangeEnd();
        int range = end - start + 1;
        var random = java.util.concurrent.ThreadLocalRandom.current();

        for (int attempt = 0; attempt < range; attempt++) {
            int port = start + random.nextInt(range);
            try (var serverSocket = new java.net.ServerSocket()) {
                serverSocket.setReuseAddress(false);
                serverSocket.bind(new java.net.InetSocketAddress("127.0.0.1", port));
                log.info("Allocated SSH port {}", port);
                return port;
            } catch (IOException e) {
                log.debug("Port {} in use, trying another", port);
            }
        }
        throw new IOException("No available SSH ports in range " + start + "-" + end);
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
                var cmd = new java.util.ArrayList<String>();
                cmd.add("ssh");
                cmd.add("-o"); cmd.add("StrictHostKeyChecking=no");
                cmd.add("-o"); cmd.add("UserKnownHostsFile=/dev/null");
                cmd.add("-o"); cmd.add("ConnectTimeout=5");
                cmd.add("-o"); cmd.add("BatchMode=yes");
                if (Files.exists(orchardKey)) {
                    cmd.add("-i"); cmd.add(orchardKey.toString());
                }
                cmd.add("-p"); cmd.add(String.valueOf(port));
                cmd.add("cultivator@" + host);
                cmd.add("echo ready");

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

    public void shutdown() {
        log.info("Shutting down QEMU provider, stopping {} VMs", runningVms.size());
        runningVms.values().forEach(Process::destroyForcibly);
        runningVms.clear();
        executor.shutdown();
    }
}
