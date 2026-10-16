package dev.orchard.nursery.qemu;

import dev.orchard.core.model.ContainerPlot;
import dev.orchard.core.model.ContainerRuntime;
import dev.orchard.core.model.ContainerSpec;
import dev.orchard.core.model.Plot;
import dev.orchard.core.model.PlotRequest;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.VmEndpoint;
import dev.orchard.core.model.VmEndpoint.Host;
import dev.orchard.core.model.VmEndpoint.InstanceId;
import dev.orchard.core.model.VmPlot;
import dev.orchard.core.model.VmSpec;
import dev.orchard.nursery.DevcontainerCliConfig;
import dev.orchard.nursery.FruitGrower;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QemuGroveProviderTest {

    @TempDir
    Path tempDir;

    private QemuGroveProvider provider;
    private final List<Process> spawnedProcesses = new ArrayList<>();

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
        provider = new QemuGroveProvider(config, cliConfig, new FruitGrower());
    }

    @AfterEach
    void tearDown() {
        for (Process p : spawnedProcesses) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }

    /**
     * Spawns a long-lived process whose own command line embeds {@code tag}, standing in for a
     * QEMU VM that {@link QemuGroveProvider#isOurQemuVm} can positively identify. The two
     * statements keep the shell from exec-replacing itself with {@code sleep}, so {@code tag}
     * stays visible in the process's argv.
     */
    private Process startAliveProcessTaggedWith(String tag) throws IOException {
        Process p = new ProcessBuilder("sh", "-c", "marker=" + tag + "; sleep 300").start();
        spawnedProcesses.add(p);
        return p;
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
        QemuGroveProvider p = new QemuGroveProvider(
            config, new DevcontainerCliConfig("0.75.0", 600, 60), new FruitGrower());

        p.reattachSurvivingVms(); // must not throw
    }

    @Test
    void reattachSurvivingVms_alivePidMatchingVm_seedlingBecomesInspectable() throws IOException {
        // Directory must be named after plot.id(), matching what plantSubstrate() writes
        VmPlot plot = VmPlot.germinate(UUID.randomUUID(), "qemu-local", PlotRequest.from(VmSpec.small()));
        Path vmDir = tempDir.resolve(plot.id().toString());
        Files.createDirectories(vmDir);
        // A live process whose command line identifies it as this plot's VM (as QEMU's would,
        // via the disk path that embeds the plot id).
        Process vm = startAliveProcessTaggedWith(plot.id().toString());
        Files.writeString(vmDir.resolve("qemu.pid"), String.valueOf(vm.pid()));

        provider.reattachSurvivingVms();

        Plot result = provider.inspect(plot.withEndpoint(new VmEndpoint(new InstanceId(plot.id().toString()), new Host("127.0.0.1"), plot.sshPort()))).join();
        assertThat(result.state()).isEqualTo(PlotState.SAPLING);
    }

    @Test
    void reattachSurvivingVms_aliveButUnrelatedPid_isNotAdopted() throws IOException {
        // PID reuse: the recorded PID is alive but now belongs to an unrelated process, not this
        // plot's QEMU VM. It must NOT be adopted — otherwise inspect()/uproot() would act on,
        // and uproot() would destroyForcibly(), a process that isn't ours.
        VmPlot plot = VmPlot.germinate(UUID.randomUUID(), "qemu-local", PlotRequest.from(VmSpec.small()));
        Path vmDir = tempDir.resolve(plot.id().toString());
        Files.createDirectories(vmDir);
        Process unrelated = startAliveProcessTaggedWith("some-other-unrelated-process");
        Files.writeString(vmDir.resolve("qemu.pid"), String.valueOf(unrelated.pid()));

        provider.reattachSurvivingVms();

        Plot result = provider.inspect(plot.withEndpoint(new VmEndpoint(new InstanceId(plot.id().toString()), new Host("127.0.0.1"), plot.sshPort()))).join();
        assertThat(result.state()).isEqualTo(PlotState.WITHERED);
    }

    @Test
    void reattachSurvivingVms_deadPid_seedlingRemainsWithered() throws IOException, InterruptedException {
        VmPlot plot = VmPlot.germinate(UUID.randomUUID(), "qemu-local", PlotRequest.from(VmSpec.small()));
        Path vmDir = tempDir.resolve(plot.id().toString());
        Files.createDirectories(vmDir);

        // Spawn a process and wait for it to exit so we get a guaranteed dead PID
        Process dead = new ProcessBuilder("true").start();
        long deadPid = dead.pid();
        dead.waitFor();
        Files.writeString(vmDir.resolve("qemu.pid"), String.valueOf(deadPid));

        provider.reattachSurvivingVms();

        Plot result = provider.inspect(plot.withEndpoint(new VmEndpoint(new InstanceId(plot.id().toString()), new Host("127.0.0.1"), plot.sshPort()))).join();
        assertThat(result.state()).isEqualTo(PlotState.WITHERED);
    }

    @Test
    void reattachSurvivingVms_malformedPidFile_skipsEntry() throws IOException {
        VmPlot plot = VmPlot.germinate(UUID.randomUUID(), "qemu-local", PlotRequest.from(VmSpec.small()));
        Path vmDir = tempDir.resolve(plot.id().toString());
        Files.createDirectories(vmDir);
        Files.writeString(vmDir.resolve("qemu.pid"), "not-a-number");

        provider.reattachSurvivingVms(); // must not throw

        Plot result = provider.inspect(plot.withEndpoint(new VmEndpoint(new InstanceId(plot.id().toString()), new Host("127.0.0.1"), plot.sshPort()))).join();
        assertThat(result.state()).isEqualTo(PlotState.WITHERED);
    }

    @Test
    void reattachSurvivingVms_dirWithoutPidFile_skipsEntry() throws IOException {
        VmPlot plot = VmPlot.germinate(UUID.randomUUID(), "qemu-local", PlotRequest.from(VmSpec.small()));
        Files.createDirectories(tempDir.resolve(plot.id().toString()));

        provider.reattachSurvivingVms(); // must not throw

        Plot result = provider.inspect(plot.withEndpoint(new VmEndpoint(new InstanceId(plot.id().toString()), new Host("127.0.0.1"), plot.sshPort()))).join();
        assertThat(result.state()).isEqualTo(PlotState.WITHERED);
    }

    @Test
    void reattachSurvivingVms_multipleVms_reattachesOnlyAlive() throws IOException, InterruptedException {
        VmPlot alivePlot = VmPlot.germinate(UUID.randomUUID(), "qemu-local", PlotRequest.from(VmSpec.small()));
        VmPlot deadPlot = VmPlot.germinate(UUID.randomUUID(), "qemu-local", PlotRequest.from(VmSpec.small()));

        Path aliveDir = tempDir.resolve(alivePlot.id().toString());
        Path deadDir = tempDir.resolve(deadPlot.id().toString());
        Files.createDirectories(aliveDir);
        Files.createDirectories(deadDir);

        Process aliveVm = startAliveProcessTaggedWith(alivePlot.id().toString());
        Files.writeString(aliveDir.resolve("qemu.pid"), String.valueOf(aliveVm.pid()));

        Process dead = new ProcessBuilder("true").start();
        long deadPid = dead.pid();
        dead.waitFor();
        Files.writeString(deadDir.resolve("qemu.pid"), String.valueOf(deadPid));

        provider.reattachSurvivingVms();

        assertThat(provider.inspect(alivePlot.withEndpoint(new VmEndpoint(new InstanceId(alivePlot.id().toString()), new Host("127.0.0.1"), alivePlot.sshPort()))).join().state())
            .isEqualTo(PlotState.SAPLING);
        assertThat(provider.inspect(deadPlot.withEndpoint(new VmEndpoint(new InstanceId(deadPlot.id().toString()), new Host("127.0.0.1"), deadPlot.sshPort()))).join().state())
            .isEqualTo(PlotState.WITHERED);
    }

    // --- inspect ---

    @Test
    void inspect_noEntryInMap_returnsWithered() {
        VmPlot germinated = VmPlot.germinate(UUID.randomUUID(), "qemu-local", PlotRequest.from(VmSpec.small()));
        VmPlot plot = germinated.withEndpoint(
            new VmEndpoint(new InstanceId("some-id"), new Host("127.0.0.1"), germinated.sshPort()));

        Plot result = provider.inspect(plot).join();

        assertThat(result.state()).isEqualTo(PlotState.WITHERED);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"water", "dormant", "uproot", "inspect"})
    void ownedMethod_containerPlot_isRefusedOnTheCallingThread(String method) {
        ContainerPlot container = new ContainerPlot(UUID.randomUUID(), UUID.randomUUID(), "docker-local",
            "c-1", PlotState.SAPLING, Instant.now(), null, Path.of("/tmp/ws"),
            ContainerRuntime.DOCKER, new ContainerSpec(2, 2048));
        Runnable call = switch (method) {
            case "water" -> () -> provider.water(container);
            case "dormant" -> () -> provider.dormant(container);
            case "uproot" -> () -> provider.uproot(container);
            case "inspect" -> () -> provider.inspect(container);
            default -> throw new IllegalArgumentException(method);
        };

        assertThatThrownBy(call::run)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("qemu-local")
            .hasMessageContaining(container.id().toString());
    }

    // --- uproot ---

    /**
     * A missing handle is the already-gone case and must stay a success path, so uproot remains
     * idempotent for the DELETE-on-ORPHANED retry flow.
     */
    @Test
    void uproot_noHandleRecorded_completesSuccessfully() {
        VmPlot plot = VmPlot.germinate(UUID.randomUUID(), "qemu-local", PlotRequest.from(VmSpec.small()));

        assertThatCode(() -> provider.uproot(plot).join()).doesNotThrowAnyException();
    }

    /**
     * A handle still alive after destroyForcibly() must make uproot's future complete
     * exceptionally, not report success while the VM keeps running.
     */
    @Test
    void uproot_handleStillAliveAfterDestroyForcibly_completesExceptionally() {
        QemuCommands neverExits = new QemuCommands() {
            @Override
            public void createDiskImage(Path image, int diskGb) throws IOException {
                Files.createFile(image);
            }

            @Override
            public void createCloudInitIso(Path iso, VmPlot plot) throws IOException {
                Files.createFile(iso);
            }

            @Override
            public int allocateSshPort() {
                return 2222;
            }

            @Override
            public Process startQemu(VmPlot s, Path diskImage, Path cloudInitIso, int sshPort) {
                return new NeverExitsProcess();
            }

            @Override
            public void awaitReachable(String host, int sshPort) {
            }
        };
        QemuConfig config = QemuConfig.builder().vmStoragePath(tempDir).build();
        QemuGroveProvider p = new QemuGroveProvider(
            config, new DevcontainerCliConfig("0.75.0", 600, 60), new FruitGrower(), neverExits);
        VmPlot plot = VmPlot.germinate(UUID.randomUUID(), "qemu-local", PlotRequest.from(VmSpec.small()));
        p.plantSubstrate(plot).join();

        CompletableFuture<Void> result = p.uproot(plot);

        assertThatThrownBy(result::join)
            .isInstanceOf(CompletionException.class)
            .hasMessageContaining(String.valueOf(NeverExitsProcess.PID));
    }

    /**
     * Stands in for a QEMU process that SIGKILL cannot dislodge: {@link #toHandle()} returns a
     * handle reporting {@code isAlive() == true} forever, whose {@code onExit()} never completes.
     * Overriding {@code toHandle()} is the documented extension point; the other abstract members
     * just satisfy the compiler.
     */
    private static class NeverExitsProcess extends Process {
        static final long PID = 999_999_999L;
        private final ProcessHandle handle = new NeverExitsProcessHandle();

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            throw new UnsupportedOperationException("not exercised by uproot");
        }

        @Override
        public int exitValue() {
            throw new IllegalThreadStateException("process has not exited");
        }

        @Override
        public void destroy() {
        }

        @Override
        public long pid() {
            return PID;
        }

        @Override
        public ProcessHandle toHandle() {
            return handle;
        }
    }

    private static class NeverExitsProcessHandle implements ProcessHandle {
        @Override
        public long pid() {
            return NeverExitsProcess.PID;
        }

        @Override
        public Optional<ProcessHandle> parent() {
            return Optional.empty();
        }

        @Override
        public Stream<ProcessHandle> children() {
            return Stream.empty();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        @Override
        public Info info() {
            return new Info() {
                @Override
                public Optional<String> command() {
                    return Optional.empty();
                }

                @Override
                public Optional<String> commandLine() {
                    return Optional.empty();
                }

                @Override
                public Optional<String[]> arguments() {
                    return Optional.empty();
                }

                @Override
                public Optional<Instant> startInstant() {
                    return Optional.empty();
                }

                @Override
                public Optional<Duration> totalCpuDuration() {
                    return Optional.empty();
                }

                @Override
                public Optional<String> user() {
                    return Optional.empty();
                }
            };
        }

        @Override
        public CompletableFuture<ProcessHandle> onExit() {
            return new CompletableFuture<>(); // never completes — the process never exits
        }

        @Override
        public boolean supportsNormalTermination() {
            return false;
        }

        @Override
        public boolean destroy() {
            return false;
        }

        @Override
        public boolean destroyForcibly() {
            return true; // signal "sent" but has no effect, as if the process ignored SIGKILL
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public int compareTo(ProcessHandle o) {
            return Long.compare(pid(), o.pid());
        }
    }
}
