package dev.orchard.trowel.devserver;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;

/**
 * Resolves the orchard-ui-backend (BFF) native binary for the dev-server.
 * Resolution order: existing ~/.orchard/bin/orchard-ui-backend (locally built or
 * previously downloaded); else download the pinned orchard-ui release for this
 * OS/arch, sha256-verified, and extract the binary from its tar.gz; else throw
 * {@link UiBackendUnavailableException} carrying local-build guidance.
 *
 * <p>orchard-ui publishes per-platform archives named
 * {@code orchard-ui-backend-<os>-<arch>.tar.gz} (each containing a single
 * {@code orchard-ui-backend} binary) plus a combined {@code checksums-sha256.txt}.
 */
public class UiBackendResolver {

    /** orchard-ui release whose orchard-ui-backend-* archives this dev-server runs. */
    public static final String DEFAULT_UI_VERSION = "0.2.0";

    /** Entry name (basename) of the binary inside each release archive. */
    private static final String BINARY_ENTRY = "orchard-ui-backend";

    private static final String CHECKSUMS_FILE = "checksums-sha256.txt";

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
        return "orchard-ui-backend-" + osArch + ".tar.gz";
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
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        String releaseDir = releaseBase + "/v" + version + "/";

        String checksums = httpGetString(client, releaseDir + CHECKSUMS_FILE);
        String expected = checksumFor(checksums, assetName());

        Files.createDirectories(binary.getParent());
        Path tmpArchive = Files.createTempFile(binary.getParent(), "orchard-ui-backend-", ".tar.gz");
        Path tmpBinary = Files.createTempFile(binary.getParent(), "orchard-ui-backend-", ".bin");
        try {
            httpGetToFile(client, releaseDir + assetName(), tmpArchive);
            String actual = sha256(tmpArchive);
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException("checksum mismatch for " + assetName()
                    + " (expected=" + expected + " actual=" + actual + ")");
            }
            extractEntry(tmpArchive, BINARY_ENTRY, tmpBinary);
            // Set the bit before the move so a chmod failure aborts without installing a
            // non-executable binary (which resolve()'s isExecutable gate would re-download forever).
            if (!tmpBinary.toFile().setExecutable(true, false)) {
                throw new IOException("failed to mark " + BINARY_ENTRY + " executable: " + binary);
            }
            Files.move(tmpBinary, binary, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmpArchive);
            Files.deleteIfExists(tmpBinary);
        }
    }

    /** Returns the sha256 hex for {@code asset} from a {@code checksums-sha256.txt} body. */
    static String checksumFor(String checksumsBody, String asset) throws IOException {
        for (String line : checksumsBody.split("\\R")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2 && parts[parts.length - 1].equals(asset)) {
                return parts[0];
            }
        }
        throw new IOException(asset + " not listed in " + CHECKSUMS_FILE);
    }

    /**
     * Extracts the regular-file entry whose basename is {@code entryName} from a
     * gzipped tar at {@code tarGz} into {@code dest}. Minimal USTAR reader: no
     * external {@code tar}, so it stays self-contained under native-image.
     */
    static void extractEntry(Path tarGz, String entryName, Path dest) throws IOException {
        try (InputStream in = Files.newInputStream(tarGz);
             GZIPInputStream gz = new GZIPInputStream(in)) {
            byte[] header = new byte[512];
            while (readFully(gz, header) == 512 && !isAllZero(header)) {
                if ((header[124] & 0x80) != 0) {
                    // GNU/PAX base-256 size encoding; the octal reader can't decode it.
                    throw new IOException("unsupported tar size encoding in " + tarGz.getFileName());
                }
                String name = cString(header, 0, 100);
                byte typeflag = header[156];
                boolean regularFile = typeflag == 0 || typeflag == '0';
                long size = parseOctal(header, 124, 12);
                long padded = ((size + 511) / 512) * 512;
                if (regularFile && basename(name).equals(entryName)) {
                    try (var out = Files.newOutputStream(dest)) {
                        copyExact(gz, out, size);
                    }
                    return;
                }
                gz.skipNBytes(padded);
            }
        }
        throw new IOException(entryName + " entry not found in " + tarGz.getFileName());
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }

    private static void copyExact(InputStream in, java.io.OutputStream out, long count) throws IOException {
        byte[] buf = new byte[8192];
        long remaining = count;
        while (remaining > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) throw new IOException("unexpected end of archive entry");
            out.write(buf, 0, n);
            remaining -= n;
        }
    }

    private static boolean isAllZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String cString(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.US_ASCII);
    }

    private static long parseOctal(byte[] b, int off, int len) {
        long value = 0;
        for (int i = off; i < off + len; i++) {
            int c = b[i] & 0xff;
            if (c >= '0' && c <= '7') {
                value = value * 8 + (c - '0');
            }
        }
        return value;
    }

    private static String basename(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
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

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            try (InputStream in = Files.newInputStream(file)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private String guidance(String cause) {
        return "Could not obtain orchard-ui-backend binary (" + assetName() + "): " + cause + "\n"
            + "Build it locally from an orchard-ui checkout:\n"
            + "  ./gradlew :backend:nativeCompile\n"
            + "  cp backend/build/native/nativeCompile/orchard-ui-backend " + binary + "\n"
            + "Then re-run, or start core only with: trowel dev-server start --no-ui";
    }

    static String osToken(String osName) {
        String n = osName.toLowerCase();
        if (n.contains("mac") || n.contains("darwin")) return "macos";
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
