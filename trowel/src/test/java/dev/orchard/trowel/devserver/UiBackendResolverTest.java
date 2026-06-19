package dev.orchard.trowel.devserver;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UiBackendResolverTest {

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
}
