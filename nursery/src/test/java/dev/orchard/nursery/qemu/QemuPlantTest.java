package dev.orchard.nursery.qemu;

import dev.orchard.core.model.Seedling;
import dev.orchard.core.model.SeedlingState;
import dev.orchard.nursery.DevcontainerCliConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QemuPlantTest {

    @TempDir Path vmStorage;

    /** Records the paths it is handed, so a transposed argument is visible. */
    private static class RecordingCommands implements QemuCommands {
        Path diskImageArg;
        Path cloudInitIsoArg;
        Path startDiskArg;
        Path startIsoArg;
        int startPortArg;
        String awaitReachableHostArg;
        int awaitReachablePortArg;
        final List<String> order = new ArrayList<>();

        @Override
        public void createDiskImage(Path image, int diskGb) throws IOException {
            order.add("createDiskImage");
            diskImageArg = image;
            Files.createFile(image);
        }

        @Override
        public void createCloudInitIso(Path iso, Seedling seedling) throws IOException {
            order.add("createCloudInitIso");
            cloudInitIsoArg = iso;
            Files.createFile(iso);
        }

        @Override
        public int allocateSshPort() {
            order.add("allocateSshPort");
            return 2222;
        }

        @Override
        public Process startQemu(Seedling s, Path diskImage, Path cloudInitIso, int sshPort) {
            order.add("startQemu");
            startDiskArg = diskImage;
            startIsoArg = cloudInitIso;
            startPortArg = sshPort;
            // A short-lived real process gives launch() a valid pid() to record, no hypervisor needed.
            try {
                return new ProcessBuilder("true").start();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        /**
         * No-op: this is what keeps these tests fast, avoiding a real socket-retry loop. Records
         * the host/port it was handed so a botched {@code resolveEndpoint} would still be caught.
         */
        @Override
        public void awaitReachable(String host, int sshPort) {
            order.add("awaitReachable");
            awaitReachableHostArg = host;
            awaitReachablePortArg = sshPort;
        }
    }

    private static Seedling germinated() {
        return Seedling.germinate(UUID.randomUUID(), Seedling.SeedlingSpec.small());
    }

    private QemuSeedlingProvider provider(QemuCommands commands) {
        QemuConfig config = QemuConfig.builder().vmStoragePath(vmStorage).build();
        return new QemuSeedlingProvider(config, new DevcontainerCliConfig("0.80.0", 600, 60), commands);
    }

    @Test
    void launch_writesDiskAndIsoIntoTheSeedlingsOwnDirectory() throws Exception {
        RecordingCommands commands = new RecordingCommands();
        Seedling seedling = germinated();

        provider(commands).plant(seedling).join();

        Path expectedDir = vmStorage.resolve(seedling.id().toString());
        assertThat(commands.diskImageArg).isEqualTo(expectedDir.resolve("disk.qcow2"));
        assertThat(commands.cloudInitIsoArg).isEqualTo(expectedDir.resolve("cloud-init.iso"));
    }

    @Test
    void launch_passesDiskAndIsoToQemuInTheRightOrder() throws Exception {
        // The regression guard: startQemu(seedling, diskImage, cloudInitIso, port). Transposing the
        // two Path arguments compiles cleanly and no other test would notice.
        RecordingCommands commands = new RecordingCommands();
        Seedling seedling = germinated();

        provider(commands).plant(seedling).join();

        assertThat(commands.startDiskArg).hasFileName("disk.qcow2");
        assertThat(commands.startIsoArg).hasFileName("cloud-init.iso");
        assertThat(commands.startPortArg).isEqualTo(2222);
    }

    @Test
    void launch_feedsResolvedEndpointIntoAwaitReachable() throws Exception {
        // Guards the same transposition hazard one layer down: resolveEndpoint's host/port must
        // reach awaitReachable unchanged.
        RecordingCommands commands = new RecordingCommands();

        provider(commands).plant(germinated()).join();

        assertThat(commands.awaitReachableHostArg).isEqualTo("127.0.0.1");
        assertThat(commands.awaitReachablePortArg).isEqualTo(2222);
    }

    @Test
    void launch_runsStepsInOrder() throws Exception {
        RecordingCommands commands = new RecordingCommands();

        provider(commands).plant(germinated()).join();

        assertThat(commands.order)
            .containsExactly("createDiskImage", "createCloudInitIso", "allocateSshPort", "startQemu", "awaitReachable");
    }

    @Test
    void launch_recordsThePidForReattachment() throws Exception {
        RecordingCommands commands = new RecordingCommands();
        Seedling seedling = germinated();

        provider(commands).plant(seedling).join();

        Path pidFile = vmStorage.resolve(seedling.id().toString()).resolve("qemu.pid");
        assertThat(pidFile).exists();
        assertThat(Files.readString(pidFile).trim()).isNotEmpty();
    }

    @Test
    void plant_returnsBlightedWhenTheDiskImageCannotBeCreated() throws Exception {
        QemuCommands failing = new RecordingCommands() {
            @Override
            public void createDiskImage(Path image, int diskGb) throws IOException {
                throw new IOException("qemu-img not found");
            }
        };

        Seedling result = provider(failing).plant(germinated()).join();

        assertThat(result.state()).isEqualTo(SeedlingState.BLIGHTED);
    }
}
