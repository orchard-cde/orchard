package dev.orchard.nursery.qemu;

import dev.orchard.core.model.Seedling;
import dev.orchard.nursery.CloudInitTemplate;
import dev.orchard.nursery.DevcontainerCliConfig;
import dev.orchard.vine.SshCommandBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Production {@link QemuCommands}: shells out to {@code qemu-img}, {@code genisoimage}/{@code
 * mkisofs}, and {@code qemu-system-*} on the local host. Method bodies are moved verbatim from
 * {@code QemuSeedlingProvider} — see that class's git history for prior behaviour.
 */
class DefaultQemuCommands implements QemuCommands {

    private static final Logger log = LoggerFactory.getLogger(DefaultQemuCommands.class);

    private final QemuConfig config;
    private final DevcontainerCliConfig devcontainerCliConfig;

    DefaultQemuCommands(QemuConfig config, DevcontainerCliConfig devcontainerCliConfig) {
        this.config = config;
        this.devcontainerCliConfig = devcontainerCliConfig;
    }

    @Override
    public void createDiskImage(Path diskImage, int sizeGb) throws IOException, InterruptedException {
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

    @Override
    public void createCloudInitIso(Path isoPath, Seedling seedling) throws IOException, InterruptedException {
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

            // Build user-data from the classpath template. The SSH block is conditional —
            // when no key is configured, ${ssh_authorized_keys_block} substitutes to empty.
            String sshBlock = QemuSeedlingProvider.buildSshAuthorizedKeysBlock(sshPubKey, seedling.authorizedKeys());
            if (sshBlock.isEmpty()) {
                log.warn("No SSH public key configured - VM will not be accessible via SSH key auth. " +
                    "Set orchard.qemu.ssh-public-key or place key at {}.pub", config.sshKeyPath());
            }
            String userData = CloudInitTemplate.render("/cloud-init/qemu.yaml.tpl", Map.of(
                "ssh_authorized_keys_block", sshBlock,
                "cli_version", devcontainerCliConfig.version()
            ));

            Files.writeString(tempDir.resolve("user-data"), userData);

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

    @Override
    public Process startQemu(Seedling seedling, Path diskImage, Path cloudInitIso, int sshPort)
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
            if (QemuPlatformDefaults.isMacOS()) {
                cmd.add("-accel"); cmd.add("hvf");
            }
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

        // Detach from the JVM's session so the VM survives a server restart or Ctrl+C
        Path setsid = Path.of("/usr/bin/setsid");
        if (Files.exists(setsid)) {
            cmd.add(0, setsid.toString());
        }

        Path qemuLog = vmDir.resolve("qemu.log");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectInput(new java.io.File("/dev/null"));
        pb.redirectOutput(qemuLog.toFile());
        pb.redirectErrorStream(true);
        log.info("Starting QEMU: {}", String.join(" ", pb.command()));
        return pb.start();
    }

    @Override
    public int allocateSshPort() throws IOException {
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

    @Override
    public void awaitReachable(String host, int port) throws IOException {
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
                var cmd = new SshCommandBuilder()
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
}
