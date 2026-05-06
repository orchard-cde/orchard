package dev.orchard.nursery.qemu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Validates QEMU prerequisites and auto-provisions the base image if missing.
 * <p>
 * This class replicates the logic from {@code scripts/setup-base-image.sh} in pure Java,
 * allowing the Orchard platform to automatically set up its QEMU environment on first run
 * when {@link QemuConfig#autoProvision()} is enabled.
 * <p>
 * This is a plain Java class with no Spring dependencies. It is intended to be called
 * from a Spring {@code ApplicationRunner} in the trellis module.
 */
public class QemuEnvironmentInitializer {

    private static final Logger log = LoggerFactory.getLogger(QemuEnvironmentInitializer.class);
    private static final String RESIZE_TARGET = "20G";
    private static final Path SSH_KEY_PATH = Path.of(System.getProperty("user.home"), ".ssh", "orchard_ed25519");

    /**
     * Main entry point. Validates QEMU prerequisites and optionally provisions
     * the base image if it doesn't exist.
     *
     * @param config the QEMU configuration
     * @throws IllegalStateException if required binaries are missing
     */
    public void initialize(QemuConfig config) {
        log.info("Initializing QEMU environment...");

        validateBinary(config.qemuBinary(), config.qemuBinary().getFileName().toString(),
                QemuPlatformDefaults.isMacOS()
                        ? "Install with: brew install qemu"
                        : "Install with: apt install qemu-system-x86");

        Path qemuImgBinary = validateBinary(config.qemuImgBinary(),
                config.qemuImgBinary().getFileName().toString(),
                QemuPlatformDefaults.isMacOS()
                        ? "Install with: brew install qemu"
                        : "Install with: apt install qemu-utils");

        checkIsoTool();
        provisionBaseImage(config, qemuImgBinary);
        provisionSshKey(config);

        log.info("QEMU environment initialization complete.");
    }

    /**
     * Validates that a binary exists and is executable. Falls back to resolving via the system
     * PATH using {@code which}. Returns the absolute path of the binary that should be used for
     * subsequent {@link ProcessBuilder} invocations. Throws if the binary cannot be found.
     */
    Path validateBinary(Path configuredPath, String binaryName, String installHint) {
        if (Files.isExecutable(configuredPath)) {
            log.info("Found binary: {}", configuredPath);
            return configuredPath;
        }

        log.debug("Binary not found at configured path {}, checking PATH for '{}'", configuredPath, binaryName);

        Optional<Path> resolved = resolveOnPath(binaryName);
        if (resolved.isPresent()) {
            log.info("Found '{}' on PATH at {}", binaryName, resolved.get());
            return resolved.get();
        }

        throw new IllegalStateException(
                String.format("Required binary '%s' not found at '%s' and not on PATH. %s",
                        binaryName, configuredPath, installHint));
    }

    /**
     * Checks whether {@code genisoimage} or {@code mkisofs} is available.
     * Logs a warning if neither is found but does not throw.
     */
    private void checkIsoTool() {
        if (isCommandAvailable("genisoimage")) {
            log.info("ISO creation tool found: genisoimage");
            return;
        }
        if (isCommandAvailable("mkisofs")) {
            log.info("ISO creation tool found: mkisofs");
            return;
        }

        String hint = QemuPlatformDefaults.isMacOS()
                ? "Install with: brew install cdrtools"
                : "Install with: apt install genisoimage";
        log.warn("Neither genisoimage nor mkisofs was found. "
                + "One of these is required to generate cloud-init ISO images for seedling provisioning. {}", hint);
    }

    /**
     * Checks whether the base image exists. If missing and auto-provision is enabled,
     * downloads the Ubuntu cloud image, converts it to qcow2, and resizes it.
     */
    private void provisionBaseImage(QemuConfig config, Path qemuImgBinary) {
        Path baseImagePath = config.baseImagePath();

        if (Files.exists(baseImagePath)) {
            log.info("Base image found at {}", baseImagePath);
            return;
        }

        if (!config.autoProvision()) {
            throw new IllegalStateException(
                    String.format("Base image not found at %s. Run scripts/setup-base-image.sh or enable auto-provision.",
                            baseImagePath));
        }

        log.info("Base image not found. Auto-provisioning...");

        // Download cloud image to a temp file in the same directory
        String arch = QemuPlatformDefaults.isAarch64() ? "arm64" : "amd64";
        Path downloadPath = baseImagePath.getParent().resolve("downloading-" + arch + ".img");

        try {
            // Create parent directory
            Files.createDirectories(baseImagePath.getParent());

            String imageUrl = QemuPlatformDefaults.ubuntuImageUrl();
            downloadFile(imageUrl, downloadPath);

            // Convert to qcow2
            log.info("Converting image to qcow2 format...");
            runCommand(qemuImgBinary.toString(),
                    "convert", "-f", "qcow2", "-O", "qcow2",
                    downloadPath.toString(), baseImagePath.toString());
            log.info("Conversion complete: {}", baseImagePath);

            // Resize
            log.info("Resizing image to {}...", RESIZE_TARGET);
            runCommand(qemuImgBinary.toString(),
                    "resize", baseImagePath.toString(), RESIZE_TARGET);
            log.info("Resize complete.");

            log.info("Base image provisioned successfully at {}", baseImagePath);

        } catch (IOException e) {
            // Clean up partial base image so next startup doesn't find a corrupt file
            cleanupPartialFile(baseImagePath);
            throw new IllegalStateException("Failed to provision base image", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cleanupPartialFile(baseImagePath);
            throw new IllegalStateException("Base image provisioning was interrupted", e);
        } finally {
            // Always clean up the downloaded temp file
            cleanupPartialFile(downloadPath);
        }
    }

    /**
     * Checks whether the Orchard SSH key exists. If missing and auto-provision is enabled,
     * generates a new ed25519 key pair.
     */
    private void provisionSshKey(QemuConfig config) {
        if (Files.exists(SSH_KEY_PATH)) {
            log.info("Orchard SSH key found at {}", SSH_KEY_PATH);
            return;
        }

        if (!config.autoProvision()) {
            log.warn("Orchard SSH key not found at {}. Generate one with: ssh-keygen -t ed25519 -f {} -N \"\" -C \"orchard-cde\"",
                    SSH_KEY_PATH, SSH_KEY_PATH);
            return;
        }

        log.info("Generating Orchard SSH key pair at {}...", SSH_KEY_PATH);
        try {
            // Ensure .ssh directory exists
            Files.createDirectories(SSH_KEY_PATH.getParent());

            runCommand("ssh-keygen", "-t", "ed25519",
                    "-f", SSH_KEY_PATH.toString(),
                    "-N", "",
                    "-C", "orchard-cde");

            Path publicKeyPath = Path.of(SSH_KEY_PATH + ".pub");
            log.info("SSH key pair generated. Public key: {}", publicKeyPath);

        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate SSH key", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SSH key generation was interrupted", e);
        }
    }

    /**
     * Silently deletes a file if it exists. Used for cleanup of partial downloads or
     * corrupt base images on failure.
     */
    private void cleanupPartialFile(Path path) {
        try {
            if (Files.deleteIfExists(path)) {
                log.debug("Cleaned up partial file: {}", path);
            }
        } catch (IOException e) {
            log.warn("Failed to clean up partial file: {}", path, e);
        }
    }

    /**
     * Checks whether a command is available on the system PATH.
     *
     * @param command the command name to check
     * @return {@code true} if the command is found
     */
    private boolean isCommandAvailable(String command) {
        return resolveOnPath(command).isPresent();
    }

    /**
     * Resolves a command to an absolute path via {@code which}. Returns {@link Optional#empty()}
     * when the command is not found or {@code which} fails.
     */
    private Optional<Path> resolveOnPath(String command) {
        try {
            Process process = new ProcessBuilder("which", command)
                    .redirectErrorStream(false)
                    .start();
            String firstLine;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                firstLine = reader.readLine();
            }
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() != 0 || firstLine == null || firstLine.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(Path.of(firstLine.trim()));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }

    /**
     * Runs an external process and throws if the exit code is non-zero.
     *
     * @param args the command and arguments
     * @throws IOException          if an I/O error occurs
     * @throws InterruptedException if the process is interrupted
     */
    private void runCommand(String... args) throws IOException, InterruptedException {
        log.debug("Running command: {}", String.join(" ", args));

        Process process = new ProcessBuilder(args)
                .redirectErrorStream(true)
                .start();

        // Consume output to prevent blocking
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
        }

        boolean finished = process.waitFor(10, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Command timed out: " + String.join(" ", args));
        }

        if (process.exitValue() != 0) {
            throw new IOException(String.format("Command failed with exit code %d: %s\nOutput: %s",
                    process.exitValue(), String.join(" ", args), output));
        }
    }

    /**
     * Downloads a file from the given URL to the specified destination path,
     * logging progress periodically.
     *
     * @param url         the URL to download from
     * @param destination the local path to save the file to
     * @throws IOException if an I/O error occurs during the download
     */
    private void downloadFile(String url, Path destination) throws IOException {
        log.info("Downloading image from {}", url);
        log.info("Destination: {}", destination);

        try {
            HttpClient client = HttpClient.newHttpClient();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            // First, send HEAD request to get content length for progress reporting
            HttpRequest headRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();

            long contentLength = -1;
            try {
                HttpResponse<Void> headResponse = client.send(headRequest, HttpResponse.BodyHandlers.discarding());
                contentLength = headResponse.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (contentLength > 0) {
                    log.info("Download size: {} MB", contentLength / (1024 * 1024));
                }
            } catch (Exception e) {
                log.debug("Could not determine download size via HEAD request", e);
            }

            // Download the file using BodyHandlers.ofInputStream for progress tracking
            HttpResponse<java.io.InputStream> response = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                throw new IOException("Download failed with HTTP status " + response.statusCode());
            }

            long totalBytes = 0;
            long lastLoggedMb = 0;
            byte[] buffer = new byte[8192];

            try (java.io.InputStream in = response.body();
                 java.io.OutputStream out = Files.newOutputStream(destination)) {
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;

                    long currentMb = totalBytes / (10 * 1024 * 1024); // every 10 MB
                    if (currentMb > lastLoggedMb) {
                        lastLoggedMb = currentMb;
                        if (contentLength > 0) {
                            long pct = (totalBytes * 100) / contentLength;
                            log.info("Download progress: {} MB / {} MB ({}%)",
                                    totalBytes / (1024 * 1024),
                                    contentLength / (1024 * 1024),
                                    pct);
                        } else {
                            log.info("Download progress: {} MB", totalBytes / (1024 * 1024));
                        }
                    }
                }
            }

            log.info("Download complete: {} MB total", totalBytes / (1024 * 1024));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download was interrupted", e);
        }
    }
}
