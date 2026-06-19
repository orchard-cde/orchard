package dev.orchard.trowel.devserver;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UiBackendResolverTest {

    @TempDir Path tempDir;

    @Test
    void osToken_mapsMacAndLinux() {
        assertThat(UiBackendResolver.osToken("Mac OS X")).isEqualTo("mac");
        assertThat(UiBackendResolver.osToken("Darwin")).isEqualTo("mac");
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
    void assetName_combinesVersionOsArch() {
        UiBackendResolver r = new UiBackendResolver(
            Path.of("/tmp/orchard-ui-backend"), "0.2.0", "http://localhost", "mac-arm64");
        assertThat(r.assetName()).isEqualTo("orchard-ui-backend-0.2.0-mac-arm64");
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Serves /v<ver>/<asset> and /v<ver>/<asset>.sha256; returns the base URL. */
    private HttpServer startRelease(byte[] binary, String checksumLine, String asset, String version) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        String base = "/v" + version + "/" + asset;
        server.createContext(base + ".sha256", ex -> {
            byte[] body = checksumLine.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body); ex.close();
        });
        server.createContext(base, ex -> {
            ex.sendResponseHeaders(200, binary.length);
            ex.getResponseBody().write(binary); ex.close();
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
        UiBackendResolver r = new UiBackendResolver(bin, "0.2.0", "http://localhost:1", "mac-arm64");

        assertThat(r.resolve()).isEqualTo(bin);
    }

    @Test
    void resolve_downloadsAndInstallsWhenChecksumMatches() throws Exception {
        byte[] binBytes = "FAKE-NATIVE-BINARY".getBytes(StandardCharsets.UTF_8);
        String asset = "orchard-ui-backend-0.2.0-mac-arm64";
        String checksum = sha256Hex(binBytes) + "  " + asset;
        HttpServer server = startRelease(binBytes, checksum, asset, "0.2.0");
        try {
            Path bin = tempDir.resolve("bin").resolve("orchard-ui-backend");
            String base = "http://localhost:" + server.getAddress().getPort();
            UiBackendResolver r = new UiBackendResolver(bin, "0.2.0", base, "mac-arm64");

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
        byte[] binBytes = "FAKE-NATIVE-BINARY".getBytes(StandardCharsets.UTF_8);
        String asset = "orchard-ui-backend-0.2.0-mac-arm64";
        String wrong = "0".repeat(64) + "  " + asset;
        HttpServer server = startRelease(binBytes, wrong, asset, "0.2.0");
        try {
            Path bin = tempDir.resolve("bin").resolve("orchard-ui-backend");
            String base = "http://localhost:" + server.getAddress().getPort();
            UiBackendResolver r = new UiBackendResolver(bin, "0.2.0", base, "mac-arm64");

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
        UiBackendResolver r = new UiBackendResolver(bin, "0.2.0", "http://localhost:1", "mac-arm64");

        assertThatThrownBy(r::resolve)
            .isInstanceOf(UiBackendUnavailableException.class)
            .hasMessageContaining("orchard-ui-backend-0.2.0-mac-arm64")
            .hasMessageContaining("--no-ui");
        assertThat(Files.exists(bin)).isFalse();
    }
}
