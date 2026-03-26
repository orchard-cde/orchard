package dev.orchard.nursery.qemu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class QemuEnvironmentInitializerTest {

    private final QemuEnvironmentInitializer initializer = new QemuEnvironmentInitializer();

    @Test
    void initialize_throwsWhenQemuBinaryMissing() {
        QemuConfig config = QemuConfig.builder()
                .qemuBinary(Path.of("/nonexistent/qemu-system-x86_64"))
                .qemuImgBinary(Path.of("/nonexistent/qemu-img"))
                .autoProvision(false)
                .build();

        assertThatThrownBy(() -> initializer.initialize(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void initialize_throwsWhenQemuImgBinaryMissing() {
        Path qemuBinary = QemuPlatformDefaults.defaultQemuBinary();
        assumeTrue(Files.isExecutable(qemuBinary),
                "QEMU not installed, skipping");

        QemuConfig config = QemuConfig.builder()
                .qemuBinary(qemuBinary)
                .qemuImgBinary(Path.of("/nonexistent/qemu-img"))
                .autoProvision(false)
                .build();

        assertThatThrownBy(() -> initializer.initialize(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void initialize_throwsWhenBaseImageMissingAndAutoProvisionDisabled() {
        Path qemuBinary = QemuPlatformDefaults.defaultQemuBinary();
        Path qemuImgBinary = QemuPlatformDefaults.defaultQemuImgBinary();
        assumeTrue(Files.isExecutable(qemuBinary),
                "QEMU not installed, skipping");
        assumeTrue(Files.isExecutable(qemuImgBinary),
                "qemu-img not installed, skipping");

        QemuConfig config = QemuConfig.builder()
                .qemuBinary(qemuBinary)
                .qemuImgBinary(qemuImgBinary)
                .baseImagePath(Path.of("/nonexistent/base.qcow2"))
                .autoProvision(false)
                .build();

        assertThatThrownBy(() -> initializer.initialize(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("setup-base-image.sh or enable auto-provision");
    }

    @Test
    void initialize_succeedsWhenAllPrerequisitesExist(@TempDir Path tempDir) throws IOException {
        Path qemuBinary = QemuPlatformDefaults.defaultQemuBinary();
        Path qemuImgBinary = QemuPlatformDefaults.defaultQemuImgBinary();
        assumeTrue(Files.isExecutable(qemuBinary),
                "QEMU not installed, skipping");
        assumeTrue(Files.isExecutable(qemuImgBinary),
                "qemu-img not installed, skipping");

        // Create a fake base image file so the initializer finds it
        Path fakeBaseImage = tempDir.resolve("base.qcow2");
        Files.writeString(fakeBaseImage, "fake-image-content");

        QemuConfig config = QemuConfig.builder()
                .qemuBinary(qemuBinary)
                .qemuImgBinary(qemuImgBinary)
                .baseImagePath(fakeBaseImage)
                .vmStoragePath(tempDir.resolve("vms"))
                .autoProvision(false)
                .build();

        assertDoesNotThrow(() -> initializer.initialize(config));
    }
}
