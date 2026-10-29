package dev.orchard.nursery.qemu;

import dev.orchard.core.model.ContainerPlot;
import dev.orchard.core.model.ContainerRuntime;
import dev.orchard.core.model.ContainerSpec;
import dev.orchard.core.model.Plot;
import dev.orchard.core.model.SeedlingSpec;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.VmEndpoint;
import dev.orchard.core.model.Host;
import dev.orchard.core.model.ProviderHandle;
import dev.orchard.core.model.ProviderType;
import dev.orchard.core.model.VmPlot;
import dev.orchard.core.model.VmSpec;
import dev.orchard.nursery.DevcontainerCliConfig;
import dev.orchard.nursery.FruitGrower;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import java.util.concurrent.TimeUnit;
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

    @Test
    void type_isQemuLocal_andTheProviderIdDerivesFromIt() {
        assertThat(provider.type()).isEqualTo(ProviderType.QEMU_LOCAL);
        assertThat(provider.getProviderId()).isEqualTo("qemu-local");
    }

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
        VmPlot plot = VmPlot.germinate(UUID.randomUUID(), "qemu-local", SeedlingSpec.from(VmSpec.small()));
        Path vmDir = tempDir.resolve(plot.id().toString());
        Files.createDirectories(vmDir);
        // A live process whose command line identifies it as this plot's VM (as QEMU's would,
        // via the disk path that embeds the plot id).
        Process vm = startAliveProcessTaggedWith(plot.id().toString());
        Files.writeString(vmDir.resolve("qemu.pid"), String.valueOf(vm.pid()));

        provider.reattachSurvivingVms();

        Plot result = provider.inspect(plot.withEndpoint(new VmEndpoint(new ProviderHandle(plot.id().toString()), new Host("127.0.0.1"), plot.sshPort()))).join();
        assertThat(result.state()).isEqualTo(PlotState.ROOTED);
    }

    @Test
    void reattachSurvivingVms_aliveButUnrelatedPid_isNotAdopted() throws IOException {
        // PID reuse: the recorded PID is alive but now belongs to an unrelated process, not this
        // plot's QEMU VM. It must NOT be adopted — otherwise inspect()/uproot() would act on,
        // and uproot() would destroyForcibly(), a process that isn't ours.
        VmPlot plot = VmPlot.germinate(UUID.randomUUID(), "qemu-local", SeedlingSpec.from(VmSpec.small()));
        Path vmDir = tempDir.resolve(plot.id().toString());
        Files.createDirectories(vmDir);
        Process unrelated = startAliveProcessTaggedWith("some-other-unrelated-process");
        Files.writeString(vmDir.resolve("qemu.pid"), String.valueOf(unrelated.pid()));

        provider.reattachSurvivingVms();

        Plot result = provider.inspect(plot.withEndpoint(new VmEndpoint(new ProviderHandle(plot.id().toString()), new Host("127.0.0.1"), plot.sshPort()))).join();
        assertThat(result.state()).isEqualTo(PlotState.WITHERED);
    }

    @Test
    void reattachSurvivingVms_deadPid_seedlingRemainsWithered() throws IOException, InterruptedException {
        VmPlot plot = VmPlot.germinate(UUID.randomUUID(), "qemu-local", SeedlingSpec.from(VmSpec.small()));
        Path vmDir = tempDir.resolve(plot.id().toString());
        Files.createDirectories(vmDir);

        // Spawn a process and wait for it to exit so we get a guaranteed dead PID
        Process dead = new ProcessBuilder("true").start();
        long deadPid = dead.pid();
        dead.waitFor();
        Files.writeString(vmDir.resolve("qemu.pid"), String.valueOf(deadPid));

        provider.reattachSurvivingVms();

        Plot result = provider.inspect(plot.withEndpoint(new VmEndpoint(new ProviderHandle(plot.id().toString()), new Host("127.0.0.1"), plot.sshPort()))).join();
        assertThat(result.state()).isEqualTo(PlotState.WITHERED);
    }

    @Test
    void reattachSurvivingVms_malformedPidFile_skipsEntry() throws IOException {
        VmPlot plot = VmPlot.germinate(UUID.randomUUID(), "qemu-local", SeedlingSpec.from(VmSpec.small()));
        Path vmDir = tempDir.resolve(plot.id().toString());
        Files.createDirectories(vmDir);
        Files.writeString(vmDir.resolve("qemu.pid"), "not-a-number");

        provider.reattachSurvivingVms(); // must not throw

        Plot result = provider.inspect(plot.withEndpoint(new VmEndpoint(new ProviderHandle(plot.id().toString()), new Host("127.0.0.1"), plot.sshPort()))).join();
        assertThat(result.state()).isEqualTo(PlotState.WITHERED);
    }

    @Test
    void reattachSurvivingVms_dirWithoutPidFile_skipsEntry() throws IOException {
        VmPlot plot = VmPlot.germinate(UUID.randomUUID(), "qemu-local", SeedlingSpec.from(VmSpec.small()));
        Files.createDirectories(tempDir.resolve(plot.id().toString()));

        provider.reattachSurvivingVms(); // must not throw

        Plot result = provider.inspect(plot.withEndpoint(new VmEndpoint(new ProviderHandle(plot.id().toString()), new Host("127.0.0.1"), plot.sshPort()))).join();
        assertThat(result.state()).isEqualTo(PlotState.WITHERED);
    }

    @Test
    void reattachSurvivingVms_multipleVms_reattachesOnlyAlive() throws IOException, InterruptedException {
        VmPlot alivePlot = VmPlot.germinate(UUID.randomUUID(), "qemu-local", SeedlingSpec.from(VmSpec.small()));
        VmPlot deadPlot = VmPlot.germinate(UUID.randomUUID(), "qemu-local", SeedlingSpec.from(VmSpec.small()));

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

        assertThat(provider.inspect(alivePlot.withEndpoint(new VmEndpoint(new ProviderHandle(alivePlot.id().toString()), new Host("127.0.0.1"), alivePlot.sshPort()))).join().state())
            .isEqualTo(PlotState.ROOTED);
        assertThat(provider.inspect(deadPlot.withEndpoint(new VmEndpoint(new ProviderHandle(deadPlot.id().toString()), new Host("127.0.0.1"), deadPlot.sshPort()))).join().state())
            .isEqualTo(PlotState.WITHERED);
    }


    @Test
    void inspect_noEntryInMap_returnsWithered() {
        VmPlot germinated = VmPlot.germinate(UUID.randomUUID(), "qemu-local", SeedlingSpec.from(VmSpec.small()));
        VmPlot plot = germinated.withEndpoint(
            new VmEndpoint(new ProviderHandle("some-id"), new Host("127.0.0.1"), germinated.sshPort()));

        Plot result = provider.inspect(plot).join();

        assertThat(result.state()).isEqualTo(PlotState.WITHERED);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"water", "dormant", "uproot", "inspect"})
    void ownedMethod_containerPlot_isRefusedOnTheCallingThread(String method) {
        ContainerPlot container = new ContainerPlot(UUID.randomUUID(), UUID.randomUUID(), "docker-local",
            "c-1", PlotState.ROOTED, Instant.now(), null, Path.of("/tmp/ws"),
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


    private class StubCommands implements QemuCommands {
        final List<String> calls = new ArrayList<>();
        Process lastProcess;
        Path startDiskArg;
        int startPortArg;
        int port = 2299;
        boolean poweroffKillsProcess;
        IOException reachFailure;
        java.util.function.Supplier<Process> processFactory = () -> {
            try {
                return startAliveProcessTaggedWith("stub-vm");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };

        @Override
        public void createDiskImage(Path image, int diskGb) throws IOException {
            calls.add("createDiskImage");
            Files.createFile(image);
        }

        @Override
        public void createCloudInitIso(Path iso, VmPlot plot) throws IOException {
            calls.add("createCloudInitIso");
            Files.createFile(iso);
        }

        @Override
        public int allocateSshPort() {
            calls.add("allocateSshPort");
            return port;
        }

        @Override
        public Process startQemu(VmPlot plot, Path diskImage, Path cloudInitIso, int sshPort) {
            calls.add("startQemu");
            startDiskArg = diskImage;
            startPortArg = sshPort;
            lastProcess = processFactory.get();
            return lastProcess;
        }

        @Override
        public void awaitReachable(String host, int sshPort) throws IOException {
            calls.add("awaitReachable");
            if (reachFailure != null) {
                throw reachFailure;
            }
        }

        @Override
        public void requestPoweroff(VmPlot plot) {
            calls.add("requestPoweroff");
            if (poweroffKillsProcess) {
                lastProcess.destroyForcibly();
            }
        }
    }

    private QemuGroveProvider providerWith(QemuCommands commands, Duration poweroffTimeout) {
        QemuConfig config = QemuConfig.builder().vmStoragePath(tempDir).build();
        return new QemuGroveProvider(config, new DevcontainerCliConfig("0.75.0", 600, 60),
            new FruitGrower(), commands, poweroffTimeout);
    }

    private static VmPlot newPlot() {
        return VmPlot.germinate(UUID.randomUUID(), "qemu-local", SeedlingSpec.from(VmSpec.small()));
    }

    /** A one-minute poweroff timeout makes the forced-kill fallback unable to finish within the bound. */
    @Test
    void dormant_poweroffExits_returnsDormant_keepsDirectory_removesPidFile() throws Exception {
        StubCommands stub = new StubCommands();
        stub.poweroffKillsProcess = true;
        QemuGroveProvider p = providerWith(stub, Duration.ofMinutes(1));
        VmPlot plot = newPlot();
        p.plantSubstrate(plot).join();
        Path vmDir = tempDir.resolve(plot.id().toString());
        assertThat(vmDir.resolve("qemu.pid")).exists();

        Plot result = p.dormant(plot.withState(PlotState.WILTING)).get(5, TimeUnit.SECONDS);

        assertThat(stub.calls).contains("requestPoweroff");
        assertThat(result.state()).isEqualTo(PlotState.DORMANT);
        assertThat(vmDir.resolve("disk.qcow2")).exists();
        assertThat(vmDir.resolve("qemu.pid")).doesNotExist();
        assertThat(stub.lastProcess.isAlive()).isFalse();
    }

    @Test
    void dormant_deadHandle_neverRequestsPoweroff() throws Exception {
        StubCommands stub = new StubCommands();
        QemuGroveProvider p = providerWith(stub, Duration.ofSeconds(10));
        VmPlot plot = newPlot();
        p.plantSubstrate(plot).join();
        stub.lastProcess.destroyForcibly().waitFor();
        Path vmDir = tempDir.resolve(plot.id().toString());

        Plot result = p.dormant(plot.withState(PlotState.WILTING)).join();

        assertThat(stub.calls).doesNotContain("requestPoweroff");
        assertThat(result.state()).isEqualTo(PlotState.DORMANT);
        assertThat(vmDir.resolve("qemu.pid")).doesNotExist();
        assertThat(vmDir.resolve("disk.qcow2")).exists();
    }

    @Test
    void dormant_poweroffTimesOut_forceKills_returnsDormant() {
        StubCommands stub = new StubCommands();
        QemuGroveProvider p = providerWith(stub, Duration.ofMillis(200));
        VmPlot plot = newPlot();
        p.plantSubstrate(plot).join();

        Plot result = p.dormant(plot.withState(PlotState.WILTING)).join();

        assertThat(result.state()).isEqualTo(PlotState.DORMANT);
        assertThat(stub.lastProcess.isAlive()).isFalse();
        assertThat(tempDir.resolve(plot.id().toString()).resolve("disk.qcow2")).exists();
    }

    @Test
    void dormant_forceKillFails_completesExceptionally() {
        StubCommands stub = new StubCommands();
        stub.processFactory = NeverExitsProcess::new;
        QemuGroveProvider p = providerWith(stub, Duration.ofMillis(200));
        VmPlot plot = newPlot();
        p.plantSubstrate(plot).join();

        assertThatThrownBy(() -> p.dormant(plot.withState(PlotState.WILTING)).join())
            .isInstanceOf(CompletionException.class)
            .hasMessageContaining(String.valueOf(NeverExitsProcess.PID));
    }

    @Test
    void water_relaunchesSameDisk_newPort_returnsRooted() throws IOException {
        StubCommands stub = new StubCommands();
        QemuGroveProvider p = providerWith(stub, Duration.ofSeconds(10));
        VmPlot plot = newPlot();
        Path vmDir = tempDir.resolve(plot.id().toString());
        Files.createDirectories(vmDir);
        Path disk = Files.createFile(vmDir.resolve("disk.qcow2"));
        Files.createFile(vmDir.resolve("cloud-init.iso"));

        Plot result = p.water(plot.withState(PlotState.DORMANT)).join();

        assertThat(stub.calls).doesNotContain("createDiskImage", "createCloudInitIso");
        assertThat(stub.startDiskArg).isEqualTo(disk);
        assertThat(stub.startPortArg).isEqualTo(2299);
        assertThat(result.state()).isEqualTo(PlotState.ROOTED);
        assertThat(((VmPlot) result).sshPort()).isEqualTo(2299);
        assertThat(Files.readString(vmDir.resolve("qemu.pid")))
            .isEqualTo(String.valueOf(stub.lastProcess.pid()));
        assertThat(p.inspect(result).join().state()).isEqualTo(PlotState.ROOTED);
    }

    @Test
    void water_liveHandleAlreadyTracked_throwsIllegalState() {
        StubCommands stub = new StubCommands();
        QemuGroveProvider p = providerWith(stub, Duration.ofSeconds(10));
        VmPlot plot = newPlot();
        p.plantSubstrate(plot).join();
        Process running = stub.lastProcess;

        assertThatThrownBy(() -> p.water(plot.withState(PlotState.DORMANT)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(plot.id().toString());
        assertThat(stub.calls).containsOnlyOnce("startQemu");
        assertThat(stub.lastProcess).isSameAs(running);
        assertThat(p.inspect(plot).join().state()).isEqualTo(PlotState.ROOTED);
    }

    @Test
    void water_unreachable_processSurvivesKill_staysTrackedWithPidFile() throws IOException {
        StubCommands stub = new StubCommands();
        stub.processFactory = NeverExitsProcess::new;
        stub.reachFailure = new IOException("ssh never came up");
        QemuGroveProvider p = providerWith(stub, Duration.ofSeconds(10));
        VmPlot plot = newPlot();
        Path vmDir = tempDir.resolve(plot.id().toString());
        Files.createDirectories(vmDir);
        Files.createFile(vmDir.resolve("disk.qcow2"));

        assertThatThrownBy(() -> p.water(plot.withState(PlotState.DORMANT)).join())
            .isInstanceOf(CompletionException.class)
            .hasMessageContaining("ssh never came up");
        assertThat(vmDir.resolve("qemu.pid")).exists();
        assertThat(p.inspect(plot).join().state()).isEqualTo(PlotState.ROOTED);
    }

    @Test
    void water_unreachable_killsProcess_removesPidFile() throws IOException {
        StubCommands stub = new StubCommands();
        stub.reachFailure = new IOException("ssh never came up");
        QemuGroveProvider p = providerWith(stub, Duration.ofSeconds(10));
        VmPlot plot = newPlot();
        Path vmDir = tempDir.resolve(plot.id().toString());
        Files.createDirectories(vmDir);
        Files.createFile(vmDir.resolve("disk.qcow2"));

        assertThatThrownBy(() -> p.water(plot.withState(PlotState.DORMANT)).join())
            .isInstanceOf(CompletionException.class);
        assertThat(stub.lastProcess.isAlive()).isFalse();
        assertThat(vmDir.resolve("qemu.pid")).doesNotExist();
        assertThat(p.inspect(plot).join().state()).isEqualTo(PlotState.DORMANT);
    }

    @ParameterizedTest
    @EnumSource(value = PlotState.class, names = "DORMANT", mode = EnumSource.Mode.EXCLUDE)
    void water_notDormant_throwsIllegalState(PlotState state) {
        StubCommands stub = new StubCommands();
        QemuGroveProvider p = providerWith(stub, Duration.ofSeconds(10));

        assertThatThrownBy(() -> p.water(newPlot().withState(state)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(stub.calls).isEmpty();
    }

    @Test
    void inspect_dirWithDiskNoPidFile_isDormant() throws IOException {
        VmPlot plot = newPlot();
        Path vmDir = tempDir.resolve(plot.id().toString());
        Files.createDirectories(vmDir);
        Files.createFile(vmDir.resolve("disk.qcow2"));

        assertThat(provider.inspect(plot).join().state()).isEqualTo(PlotState.DORMANT);
    }

    @Test
    void inspect_crashedVmWithStalePidFile_isDormant() throws IOException, InterruptedException {
        VmPlot plot = newPlot();
        Path vmDir = tempDir.resolve(plot.id().toString());
        Files.createDirectories(vmDir);
        Files.createFile(vmDir.resolve("disk.qcow2"));
        Process dead = new ProcessBuilder("true").start();
        dead.waitFor();
        Files.writeString(vmDir.resolve("qemu.pid"), String.valueOf(dead.pid()));
        provider.reattachSurvivingVms();

        assertThat(provider.inspect(plot).join().state()).isEqualTo(PlotState.DORMANT);
    }

    @Test
    void inspect_noDirectory_isWithered() {
        assertThat(provider.inspect(newPlot()).join().state()).isEqualTo(PlotState.WITHERED);
    }

    @Test
    void inspect_aliveProcess_isRooted() {
        QemuGroveProvider p = providerWith(new StubCommands(), Duration.ofSeconds(10));
        VmPlot plot = newPlot();
        p.plantSubstrate(plot).join();

        assertThat(p.inspect(plot).join().state()).isEqualTo(PlotState.ROOTED);
    }

    @Test
    void uproot_dormantPlot_deletesDirectory() throws IOException {
        VmPlot plot = newPlot();
        Path vmDir = tempDir.resolve(plot.id().toString());
        Files.createDirectories(vmDir);
        Files.createFile(vmDir.resolve("disk.qcow2"));

        provider.uproot(plot.withState(PlotState.DORMANT)).join();

        assertThat(vmDir).doesNotExist();
    }

    /**
     * A missing handle is the already-gone case and must stay a success path, so uproot remains
     * idempotent for the DELETE-on-ORPHANED retry flow.
     */
    @Test
    void uproot_noHandleRecorded_completesSuccessfully() {
        VmPlot plot = VmPlot.germinate(UUID.randomUUID(), "qemu-local", SeedlingSpec.from(VmSpec.small()));

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

            @Override
            public void requestPoweroff(VmPlot plot) {
            }
        };
        QemuConfig config = QemuConfig.builder().vmStoragePath(tempDir).build();
        QemuGroveProvider p = new QemuGroveProvider(
            config, new DevcontainerCliConfig("0.75.0", 600, 60), new FruitGrower(), neverExits,
            Duration.ofSeconds(60));
        VmPlot plot = VmPlot.germinate(UUID.randomUUID(), "qemu-local", SeedlingSpec.from(VmSpec.small()));
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
