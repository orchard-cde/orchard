package dev.orchard.trowel.devserver;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;

/**
 * Resolves the orchard-ui-backend (BFF) native binary for dev-server.
 * Resolution order: an existing ~/.orchard/bin/orchard-ui-backend (locally built or
 * previously downloaded); else download the pinned release for this OS/arch, sha256-verified;
 * else throw {@link UiBackendUnavailableException} carrying local-build guidance.
 */
public class UiBackendResolver {

    /**
     * First orchard-ui release that publishes orchard-ui-backend-* binaries.
     * No such release exists yet, so the download path 404s and falls back to local-build
     * guidance — expected. Bump this when the first BFF binary release lands.
     */
    public static final String DEFAULT_UI_VERSION = "0.1.0";

    private static final String DEFAULT_RELEASE_BASE =
        "https://github.com/orchard-cde/orchard-ui/releases/download";

    private final Path binary;
    private final String version;
    private final String releaseBase;
    private final String osArch;

    public UiBackendResolver(Path binary, String version) {
        this(binary, version,
            System.getProperty("orchard.ui.releaseBase", DEFAULT_RELEASE_BASE),
            osToken(System.getProperty("os.name")) + "-" + archToken(System.getProperty("os.arch")));
    }

    // Test seam: inject release base URL and os-arch token.
    UiBackendResolver(Path binary, String version, String releaseBase, String osArch) {
        this.binary = binary;
        this.version = version;
        this.releaseBase = releaseBase;
        this.osArch = osArch;
    }

    public String assetName() {
        return "orchard-ui-backend-" + version + "-" + osArch;
    }

    public Path resolve() throws UiBackendUnavailableException {
        if (Files.isExecutable(binary)) {
            return binary;
        }
        try {
            download();
            return binary;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String cause = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new UiBackendUnavailableException(guidance(cause), e);
        }
    }

    private void download() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        String base = releaseBase + "/v" + version + "/" + assetName();

        String checksumLine = httpGetString(client, base + ".sha256");
        String expected = checksumLine.trim().split("\\s+")[0];

        Files.createDirectories(binary.getParent());
        Path tmp = Files.createTempFile(binary.getParent(), "orchard-ui-backend-", ".download");
        try {
            httpGetToFile(client, base, tmp);
            String actual = sha256(tmp);
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException("checksum mismatch for " + assetName()
                    + " (expected=" + expected + " actual=" + actual + ")");
            }
            Files.move(tmp, binary, StandardCopyOption.REPLACE_EXISTING);
            binary.toFile().setExecutable(true, false);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String httpGetString(HttpClient client, String url) throws IOException, InterruptedException {
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IOException("GET " + url + " -> HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    private static void httpGetToFile(HttpClient client, String url, Path dest) throws IOException, InterruptedException {
        HttpResponse<Path> resp = client.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofFile(dest));
        if (resp.statusCode() >= 400) {
            throw new IOException("GET " + url + " -> HTTP " + resp.statusCode());
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private String guidance(String cause) {
        return "Could not obtain the orchard-ui-backend binary (" + assetName() + "): " + cause + "\n"
            + "No published binary for this platform yet. Build it locally from your orchard-ui checkout:\n"
            + "  ./gradlew :backend:nativeCompile\n"
            + "  cp backend/build/native/nativeCompile/orchard-ui-backend " + binary + "\n"
            + "Then re-run, or start core only with: trowel dev-server start --no-ui";
    }

    static String osToken(String osName) {
        String n = osName.toLowerCase();
        if (n.contains("mac") || n.contains("darwin")) return "mac";
        if (n.contains("linux")) return "linux";
        throw new IllegalStateException("Unsupported OS for orchard-ui-backend: " + osName);
    }

    static String archToken(String osArch) {
        String a = osArch.toLowerCase();
        if (a.equals("x86_64") || a.equals("amd64")) return "amd64";
        if (a.equals("aarch64") || a.equals("arm64")) return "arm64";
        throw new IllegalStateException("Unsupported architecture for orchard-ui-backend: " + osArch);
    }
}
