package dev.orchard.trowel.devserver;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.zip.GZIPOutputStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UiBackendResolverTest {

    @TempDir Path tempDir;

    @Test
    void osToken_mapsMacAndLinux() {
        assertThat(UiBackendResolver.osToken("Mac OS X")).isEqualTo("macos");
        assertThat(UiBackendResolver.osToken("Darwin")).isEqualTo("macos");
        assertThat(UiBackendResolver.osToken("Linux")).isEqualTo("linux");
    }

    @Test
    void osToken_rejectsUnknown() {
        assertThatThrownBy(() -> UiBackendResolver.osToken("Windows 11"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void archToken_mapsX86AndArm() {
        assertThat(UiBackendResolver.archToken("x86_64")).isEqualTo("amd64");
        assertThat(UiBackendResolver.archToken("amd64")).isEqualTo("amd64");
        assertThat(UiBackendResolver.archToken("aarch64")).isEqualTo("arm64");
        assertThat(UiBackendResolver.archToken("arm64")).isEqualTo("arm64");
    }

    @Test
    void archToken_rejectsUnknown() {
        assertThatThrownBy(() -> UiBackendResolver.archToken("ppc64le"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void assetName_isOsArchTarGzWithoutVersionSegment() {
        UiBackendResolver r = new UiBackendResolver(
            Path.of("/tmp/orchard-ui-backend"), "0.2.0", "http://localhost", "macos-arm64");
        assertThat(r.assetName()).isEqualTo("orchard-ui-backend-macos-arm64.tar.gz");
    }

    @Test
    void extractEntry_rejectsNonRegularFileEntryWithMatchingName() throws Exception {
        // A symlink/hardlink entry (typeflag '2') named like the binary carries no data;
        // accepting it would install an empty file that still passes the archive checksum.
        byte[] archive = tarGz("orchard-ui-backend", new byte[0], '2', false);
        Path tar = tempDir.resolve("a.tar.gz");
        Files.write(tar, archive);
        Path dest = tempDir.resolve("out");

        assertThatThrownBy(() -> UiBackendResolver.extractEntry(tar, "orchard-ui-backend", dest))
            .isInstanceOf(IOException.class);
        assertThat(Files.exists(dest)).isFalse();
    }

    @Test
    void extractEntry_rejectsGnuBase256SizeEncoding() throws Exception {
        byte[] archive = tarGz("orchard-ui-backend", "data".getBytes(StandardCharsets.UTF_8), '0', true);
        Path tar = tempDir.resolve("b.tar.gz");
        Files.write(tar, archive);
        Path dest = tempDir.resolve("out2");

        assertThatThrownBy(() -> UiBackendResolver.extractEntry(tar, "orchard-ui-backend", dest))
            .isInstanceOf(IOException.class);
        assertThat(Files.exists(dest)).isFalse();
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Builds a gzipped tar carrying a single regular-file entry. */
    private static byte[] tarGz(String entryName, byte[] content) throws IOException {
        return tarGz(entryName, content, '0', false);
    }

    /**
     * Builds a single-entry gzipped tar with a chosen USTAR typeflag, optionally writing
     * the size field with the GNU base-256 (high-bit) encoding instead of ASCII octal.
     */
    private static byte[] tarGz(String entryName, byte[] content, char typeflag, boolean base256Size) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            byte[] header = new byte[512];
            byte[] name = entryName.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(name, 0, header, 0, name.length);
            putOctal(header, 100, 8, 0755);   // mode
            putOctal(header, 108, 8, 0);       // uid
            putOctal(header, 116, 8, 0);       // gid
            if (base256Size) {
                header[124] = (byte) 0x80;     // GNU base-256 marker; remaining bytes encode size
                long v = content.length;
                for (int i = 135; i >= 125 && v > 0; i--) { header[i] = (byte) (v & 0xff); v >>= 8; }
            } else {
                putOctal(header, 124, 12, content.length);
            }
            putOctal(header, 136, 12, 0);      // mtime
            header[156] = (byte) typeflag;
            for (int i = 148; i < 156; i++) header[i] = ' ';
            int chk = 0;
            for (byte b : header) chk += (b & 0xff);
            byte[] c = String.format("%06o", chk).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(c, 0, header, 148, 6);
            header[154] = 0;
            header[155] = ' ';
            gz.write(header);
            gz.write(content);
            int pad = (512 - (content.length % 512)) % 512;
            if (pad > 0) gz.write(new byte[pad]);
            gz.write(new byte[1024]); // two zero blocks: end of archive
        }
        return bos.toByteArray();
    }

    private static void putOctal(byte[] h, int off, int len, long value) {
        String s = String.format("%0" + (len - 1) + "o", value);
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, h, off, b.length);
        h[off + len - 1] = 0;
    }

    /** Serves /v&lt;ver&gt;/&lt;asset&gt; and /v&lt;ver&gt;/checksums-sha256.txt; returns the base URL. */
    private HttpServer startRelease(byte[] asset, String checksumsBody, String assetName, String version) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        String prefix = "/v" + version + "/";
        server.createContext(prefix + "checksums-sha256.txt", ex -> {
            byte[] body = checksumsBody.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body); ex.close();
        });
        server.createContext(prefix + assetName, ex -> {
            ex.sendResponseHeaders(200, asset.length);
            ex.getResponseBody().write(asset); ex.close();
        });
        server.start();
        return server;
    }

    @Test
    void resolve_returnsExistingExecutableWithoutNetwork() throws Exception {
        Path bin = tempDir.resolve("bin").resolve("orchard-ui-backend");
        Files.createDirectories(bin.getParent());
        Files.writeString(bin, "#!/bin/sh\necho hi\n");
        bin.toFile().setExecutable(true, false);
        // Release base points nowhere reachable; must not be contacted.
        UiBackendResolver r = new UiBackendResolver(bin, "0.2.0", "http://localhost:1", "macos-arm64");

        assertThat(r.resolve()).isEqualTo(bin);
    }

    @Test
    void resolve_downloadsExtractsAndInstallsWhenChecksumMatches() throws Exception {
        byte[] binBytes = "FAKE-NATIVE-BINARY".getBytes(StandardCharsets.UTF_8);
        byte[] archive = tarGz("orchard-ui-backend", binBytes);
        String assetName = "orchard-ui-backend-macos-arm64.tar.gz";
        // Combined checksums file lists the .tar.gz, two-space separated, alongside an unrelated entry.
        String checksums = sha256Hex("other".getBytes(StandardCharsets.UTF_8)) + "  something-else.tar.gz\n"
            + sha256Hex(archive) + "  " + assetName + "\n";
        HttpServer server = startRelease(archive, checksums, assetName, "0.2.0");
        try {
            Path bin = tempDir.resolve("bin").resolve("orchard-ui-backend");
            String base = "http://localhost:" + server.getAddress().getPort();
            UiBackendResolver r = new UiBackendResolver(bin, "0.2.0", base, "macos-arm64");

            Path resolved = r.resolve();

            assertThat(resolved).isEqualTo(bin);
            assertThat(Files.isExecutable(bin)).isTrue();
            assertThat(Files.readAllBytes(bin)).isEqualTo(binBytes);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolve_throwsAndDoesNotInstallOnChecksumMismatch() throws Exception {
        byte[] archive = tarGz("orchard-ui-backend", "FAKE-NATIVE-BINARY".getBytes(StandardCharsets.UTF_8));
        String assetName = "orchard-ui-backend-macos-arm64.tar.gz";
        String wrong = "0".repeat(64) + "  " + assetName + "\n";
        HttpServer server = startRelease(archive, wrong, assetName, "0.2.0");
        try {
            Path bin = tempDir.resolve("bin").resolve("orchard-ui-backend");
            String base = "http://localhost:" + server.getAddress().getPort();
            UiBackendResolver r = new UiBackendResolver(bin, "0.2.0", base, "macos-arm64");

            assertThatThrownBy(r::resolve)
                .isInstanceOf(UiBackendUnavailableException.class)
                .hasMessageContaining("./gradlew :backend:nativeCompile");
            assertThat(Files.exists(bin)).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolve_throwsWithBuildGuidanceWhenAssetMissing() throws Exception {
        // No server / unreachable base -> download fails (404/connection) -> guidance.
        Path bin = tempDir.resolve("bin").resolve("orchard-ui-backend");
        UiBackendResolver r = new UiBackendResolver(bin, "0.2.0", "http://localhost:1", "macos-arm64");

        assertThatThrownBy(r::resolve)
            .isInstanceOf(UiBackendUnavailableException.class)
            .hasMessageContaining("orchard-ui-backend-macos-arm64.tar.gz")
            .hasMessageContaining("--no-ui");
        assertThat(Files.exists(bin)).isFalse();
    }
}
