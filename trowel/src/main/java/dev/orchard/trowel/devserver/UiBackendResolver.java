package dev.orchard.trowel.devserver;

import java.nio.file.Path;

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
