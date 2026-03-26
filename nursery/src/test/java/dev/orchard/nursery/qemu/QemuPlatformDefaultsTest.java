package dev.orchard.nursery.qemu;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class QemuPlatformDefaultsTest {

    @Test
    void isMacOS_returnsConsistentResult() {
        boolean first = QemuPlatformDefaults.isMacOS();
        boolean second = QemuPlatformDefaults.isMacOS();
        assertThat(first).isEqualTo(second);
    }

    @Test
    void isAarch64_returnsConsistentResult() {
        boolean first = QemuPlatformDefaults.isAarch64();
        boolean second = QemuPlatformDefaults.isAarch64();
        assertThat(first).isEqualTo(second);
    }

    @Test
    void defaultQemuBinary_returnsNonNullPath() {
        assertThat(QemuPlatformDefaults.defaultQemuBinary()).isNotNull();
    }

    @Test
    void defaultQemuBinary_containsQemuSystem() {
        Path binary = QemuPlatformDefaults.defaultQemuBinary();
        assertThat(binary.getFileName().toString()).contains("qemu-system");
    }

    @Test
    void defaultQemuImgBinary_containsQemuImg() {
        Path binary = QemuPlatformDefaults.defaultQemuImgBinary();
        assertThat(binary.getFileName().toString()).isEqualTo("qemu-img");
    }

    @Test
    void defaultBaseImagePath_endsWithBaseQcow2() {
        Path path = QemuPlatformDefaults.defaultBaseImagePath();
        assertThat(path.getFileName().toString()).isEqualTo("base.qcow2");
    }

    @Test
    void defaultBaseImagePath_isUnderBaseImageDir() {
        Path baseImagePath = QemuPlatformDefaults.defaultBaseImagePath();
        Path baseImageDir = QemuPlatformDefaults.defaultBaseImageDir();
        assertThat(baseImagePath).startsWith(baseImageDir);
    }

    @Test
    void defaultVmStoragePath_returnsNonNull() {
        assertThat(QemuPlatformDefaults.defaultVmStoragePath()).isNotNull();
    }

    @Test
    void defaultEnableKvm_falseOnMacOS() {
        if (QemuPlatformDefaults.isMacOS()) {
            assertThat(QemuPlatformDefaults.defaultEnableKvm()).isFalse();
        } else {
            assertThat(QemuPlatformDefaults.defaultEnableKvm()).isTrue();
        }
    }

    @Test
    void defaultSerialOutput_matchesPlatform() {
        if (QemuPlatformDefaults.isMacOS()) {
            assertThat(QemuPlatformDefaults.defaultSerialOutput()).isEqualTo("file");
        } else {
            assertThat(QemuPlatformDefaults.defaultSerialOutput()).isEqualTo("stdio");
        }
    }

    @Test
    void ubuntuImageUrl_containsArchitecture() {
        String url = QemuPlatformDefaults.ubuntuImageUrl();
        if (QemuPlatformDefaults.isAarch64()) {
            assertThat(url).contains("arm64");
        } else {
            assertThat(url).contains("amd64");
        }
    }

    @Test
    void ubuntuImageUrl_isValidHttpsUrl() {
        assertThat(QemuPlatformDefaults.ubuntuImageUrl()).startsWith("https://");
    }
}
