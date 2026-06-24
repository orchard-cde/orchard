package dev.orchard.nursery.qemu;

import dev.orchard.core.model.Seedling;
import dev.orchard.core.model.Seedling.SeedlingSpec;
import dev.orchard.core.model.SeedlingState;
import dev.orchard.nursery.DevcontainerCliConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QemuSeedlingProviderTest {

    @TempDir
    Path tempDir;

    private QemuSeedlingProvider provider;

    @BeforeEach
    void setUp() {
        QemuConfig config = QemuConfig.builder()
            .vmStoragePath(tempDir)
            .qemuBinary(Path.of("/usr/bin/qemu-system-x86_64"))
            .qemuImgBinary(Path.of("/usr/bin/qemu-img"))
            .baseImagePath(tempDir.resolve("base.qcow2"))
            .cloudInitTemplatePath(tempDir.resolve("cloud-init"))
            .sshPortRangeStart(49152)
            .sshPortRangeEnd(49999)
            .sshKeyPath(tempDir.resolve("orchard_ed25519"))
            .build();

        DevcontainerCliConfig cliConfig = new DevcontainerCliConfig("0.75.0", 600, 60);
        provider = new QemuSeedlingProvider(config, cliConfig);
    }

    // --- reattachSurvivingVms ---

    @Test
    void reattachSurvivingVms_noStorageDir_isNoop() {
        QemuConfig config = QemuConfig.builder()
            .vmStoragePath(tempDir.resolve("nonexistent"))
            .qemuBinary(Path.of("/usr/bin/qemu-system-x86_64"))
            .qemuImgBinary(Path.of("/usr/bin/qemu-img"))
            .baseImagePath(tempDir.resolve("base.qcow2"))
            .cloudInitTemplatePath(tempDir.resolve("cloud-init"))
            .sshPortRangeStart(49152)
            .sshPortRangeEnd(49999)
            .sshKeyPath(tempDir.resolve("orchard_ed25519"))
            .build();
        QemuSeedlingProvider p = new QemuSeedlingProvider(config, new DevcontainerCliConfig("0.75.0", 600, 60));

        p.reattachSurvivingVms(); // must not throw
    }

    @Test
    void reattachSurvivingVms_alivePid_seedlingBecomesInspectable() throws IOException {
        // Directory must be named after seedling.id(), matching what plant() writes
        Seedling seedling = Seedling.germinate(UUID.randomUUID(), SeedlingSpec.small());
        Path vmDir = tempDir.resolve(seedling.id().toString());
        Files.createDirectories(vmDir);
        Files.writeString(vmDir.resolve("qemu.pid"), String.valueOf(ProcessHandle.current().pid()));

        provider.reattachSurvivingVms();

        Seedling result = provider.inspect(seedling.withProviderDetails(seedling.id().toString(), "127.0.0.1")).join();
        assertThat(result.state()).isEqualTo(SeedlingState.SAPLING);
    }

    @Test
    void reattachSurvivingVms_deadPid_seedlingRemainsWithered() throws IOException, InterruptedException {
        Seedling seedling = Seedling.germinate(UUID.randomUUID(), SeedlingSpec.small());
        Path vmDir = tempDir.resolve(seedling.id().toString());
        Files.createDirectories(vmDir);

        // Spawn a process and wait for it to exit so we get a guaranteed dead PID
        Process dead = new ProcessBuilder("true").start();
        long deadPid = dead.pid();
        dead.waitFor();
        Files.writeString(vmDir.resolve("qemu.pid"), String.valueOf(deadPid));

        provider.reattachSurvivingVms();

        Seedling result = provider.inspect(seedling.withProviderDetails(seedling.id().toString(), "127.0.0.1")).join();
        assertThat(result.state()).isEqualTo(SeedlingState.WITHERED);
    }

    @Test
    void reattachSurvivingVms_malformedPidFile_skipsEntry() throws IOException {
        Seedling seedling = Seedling.germinate(UUID.randomUUID(), SeedlingSpec.small());
        Path vmDir = tempDir.resolve(seedling.id().toString());
        Files.createDirectories(vmDir);
        Files.writeString(vmDir.resolve("qemu.pid"), "not-a-number");

        provider.reattachSurvivingVms(); // must not throw

        Seedling result = provider.inspect(seedling.withProviderDetails(seedling.id().toString(), "127.0.0.1")).join();
        assertThat(result.state()).isEqualTo(SeedlingState.WITHERED);
    }

    @Test
    void reattachSurvivingVms_dirWithoutPidFile_skipsEntry() throws IOException {
        Seedling seedling = Seedling.germinate(UUID.randomUUID(), SeedlingSpec.small());
        Files.createDirectories(tempDir.resolve(seedling.id().toString()));

        provider.reattachSurvivingVms(); // must not throw

        Seedling result = provider.inspect(seedling.withProviderDetails(seedling.id().toString(), "127.0.0.1")).join();
        assertThat(result.state()).isEqualTo(SeedlingState.WITHERED);
    }

    @Test
    void reattachSurvivingVms_multipleVms_reattachesOnlyAlive() throws IOException, InterruptedException {
        Seedling aliveSeedling = Seedling.germinate(UUID.randomUUID(), SeedlingSpec.small());
        Seedling deadSeedling = Seedling.germinate(UUID.randomUUID(), SeedlingSpec.small());

        Path aliveDir = tempDir.resolve(aliveSeedling.id().toString());
        Path deadDir = tempDir.resolve(deadSeedling.id().toString());
        Files.createDirectories(aliveDir);
        Files.createDirectories(deadDir);

        Files.writeString(aliveDir.resolve("qemu.pid"), String.valueOf(ProcessHandle.current().pid()));

        Process dead = new ProcessBuilder("true").start();
        long deadPid = dead.pid();
        dead.waitFor();
        Files.writeString(deadDir.resolve("qemu.pid"), String.valueOf(deadPid));

        provider.reattachSurvivingVms();

        assertThat(provider.inspect(aliveSeedling.withProviderDetails(aliveSeedling.id().toString(), "127.0.0.1")).join().state())
            .isEqualTo(SeedlingState.SAPLING);
        assertThat(provider.inspect(deadSeedling.withProviderDetails(deadSeedling.id().toString(), "127.0.0.1")).join().state())
            .isEqualTo(SeedlingState.WITHERED);
    }

    // --- inspect ---

    @Test
    void inspect_noEntryInMap_returnsWithered() {
        Seedling seedling = Seedling.germinate(UUID.randomUUID(), SeedlingSpec.small())
            .withProviderDetails("some-id", "127.0.0.1");

        Seedling result = provider.inspect(seedling).join();

        assertThat(result.state()).isEqualTo(SeedlingState.WITHERED);
    }
}
